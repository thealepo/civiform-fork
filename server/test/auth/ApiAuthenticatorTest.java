package auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static play.test.Helpers.fakeRequest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.typesafe.config.ConfigFactory;
import io.ebean.DB;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import models.ApiKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.exception.BadCredentialsException;
import org.pac4j.play.PlayWebContext;
import org.slf4j.LoggerFactory;
import play.Application;
import play.ApplicationLoader;
import play.Environment;
import play.api.inject.BindingKey;
import play.cache.NamedCache;
import play.cache.SyncCacheApi;
import play.inject.guice.GuiceApplicationBuilder;
import play.inject.guice.GuiceApplicationLoader;
import play.mvc.Http;
import play.test.Helpers;
import services.apikey.ApiKeyService;
import support.ResourceCreator;

public class ApiAuthenticatorTest {
  @Inject Application application;

  @Inject
  @NamedCache("api-keys")
  SyncCacheApi cacheApi;

  ResourceCreator resourceCreator;
  @Inject ApiAuthenticator apiAuthenticator;

  private static final String keyId = "keyId";
  private static final String secret = "secret";
  private static final String validRawCredentials = keyId + ":" + secret;
  private static final SessionStore MOCK_SESSION_STORE = Mockito.mock(SessionStore.class);
  private ApiKey apiKey;
  private Injector injector;

  @Before
  public void setUp() {
    // Most test classes extend ResetPostgres which accesses an injector via the
    // application provided fakeApplication(). That injector is a play.inject.Injector
    // which makes it much less easy to inject things that are annotated with annotations
    // that take arguments, such as @NamedCache("api-cache'). Guice makes that fairly easy
    // though, hence this test class uses the guice injector directly.
    GuiceApplicationBuilder builder =
        new GuiceApplicationLoader().builder(new ApplicationLoader.Context(Environment.simple()));
    injector = Guice.createInjector(builder.applicationModule());
    injector.injectMembers(this);

    resourceCreator =
        new ResourceCreator(
            new play.inject.Injector() {
              @Override
              public <T> T instanceOf(Class<T> clazz) {
                return injector.getInstance(clazz);
              }

              @Override
              public <T> T instanceOf(BindingKey<T> key) {
                return null;
              }

              @Override
              public play.api.inject.Injector asScala() {
                return null;
              }
            });

    Helpers.start(application);

    DB.getDefault().truncate("api_keys");
    apiKey = resourceCreator.createActiveApiKey("test-key", keyId, secret);
    cacheApi.remove(keyId);
  }

  @After
  public void tearDown() {
    Helpers.stop(application);
  }

  @Test
  public void resolveClientIp_direct() {
    var authenticator =
        new ApiAuthenticator(
            injector.getProvider(ApiKeyService.class),
            ConfigFactory.parseMap(ImmutableMap.of("client_ip_type", "DIRECT")));

    var request =
        new FakeRequestBuilder()
            .withRemoteAddress("4.4.4.4")
            .withXForwadedFor("3.3.3.3, 2.2.2.2")
            .build();

    assertThat(authenticator.resolveClientIp(new PlayWebContext(request))).isEqualTo("4.4.4.4");
  }

  @Test
  public void resolveClientIp_forwarded() {
    var authenticator =
        new ApiAuthenticator(
            injector.getProvider(ApiKeyService.class),
            ConfigFactory.parseMap(ImmutableMap.of("client_ip_type", "FORWARDED")));

    var request = new FakeRequestBuilder().withXForwadedFor("3.3.3.3, 2.2.2.2").build();

    assertThat(authenticator.resolveClientIp(new PlayWebContext(request))).isEqualTo("2.2.2.2");
  }

  @Test
  public void resolveClientIp_forwarded_no_header() {
    var authenticator =
        new ApiAuthenticator(
            injector.getProvider(ApiKeyService.class),
            ConfigFactory.parseMap(ImmutableMap.of("client_ip_type", "FORWARDED")));

    var request = new FakeRequestBuilder().withRemoteAddress("3.3.3.3").build();

    assertThatThrownBy(() -> authenticator.resolveClientIp(new PlayWebContext(request)))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("CLIENT_IP_TYPE is FORWARDED but no value found for X-Forwarded-For header!");
  }

  @Test
  public void validate_success_direct() {
    apiAuthenticator.validate(
        new UsernamePasswordCredentials(keyId, secret),
        new PlayWebContext(
            new FakeRequestBuilder().withRawCredentials(validRawCredentials).build()),
        MOCK_SESSION_STORE);

    Optional<Optional<ApiKey>> cacheEntry = cacheApi.get(keyId);
    Optional<ApiKey> cachedMaybeKey = cacheEntry.get();
    assertThat(cachedMaybeKey.get().id).isEqualTo(apiKey.id);
  }

  @Test
  public void validate_success_forwarded() {
    var authenticator =
        new ApiAuthenticator(
            injector.getProvider(ApiKeyService.class),
            ConfigFactory.parseMap(ImmutableMap.of("client_ip_type", "FORWARDED")));
    apiKey.setSubnet("3.3.3.3/32");
    apiKey.save();

    authenticator.validate(
        new UsernamePasswordCredentials(keyId, secret),
        new PlayWebContext(
            new FakeRequestBuilder()
                .withRawCredentials(validRawCredentials)
                .withXForwadedFor("2.2.2.2, 3.3.3.3")
                .build()),
        MOCK_SESSION_STORE);

    Optional<Optional<ApiKey>> cacheEntry = cacheApi.get(keyId);
    Optional<ApiKey> cachedMaybeKey = cacheEntry.get();
    assertThat(cachedMaybeKey.get().id).isEqualTo(apiKey.id);
  }

  @Test
  public void validate_invalidKeyId() {
    String rawCredentials = "wrong" + ":" + secret;

    assertBadCredentialsException(
        new FakeRequestBuilder().withRawCredentials(rawCredentials).build(),
        new UsernamePasswordCredentials("wrong", secret),
        "API key does not exist: wrong");
  }

  @Test
  public void validate_retiredKey() {
    apiKey.retire("test-user");
    apiKey.save();

    assertBadCredentialsException(
        new FakeRequestBuilder().withRawCredentials(validRawCredentials).build(),
        "API key is retired: " + keyId);
  }

  @Test
  public void validate_isExpired() {
    // Set the expiration to the past, any time in the past will suffice.
    apiKey.setExpiration(Instant.now().minusSeconds(100));
    apiKey.save();

    assertBadCredentialsException(
        new FakeRequestBuilder().withRawCredentials(validRawCredentials).build(),
        "API key is expired: " + keyId);
  }

  @Test
  public void validate_ipNotInSubnet() {
    apiKey.setSubnet("2.2.2.2/30,3.3.3.3/32");
    apiKey.save();

    assertBadCredentialsException(
        new FakeRequestBuilder()
            .withRemoteAddress("4.4.4.4")
            .withRawCredentials(validRawCredentials)
            .build(),
        String.format(
            "Resolved IP 4.4.4.4 is not in allowed range for key ID: %s, which is \"%s\"",
            keyId, "2.2.2.2/30,3.3.3.3/32"));
  }

  @Test
  public void validate_ipNotInSubnet_forwarded() {
    var authenticator =
        new ApiAuthenticator(
            injector.getProvider(ApiKeyService.class),
            ConfigFactory.parseMap(ImmutableMap.of("client_ip_type", "FORWARDED")));

    apiKey.setSubnet("2.2.2.2/30,3.3.3.3/32");
    apiKey.save();

    assertBadCredentialsException(
        authenticator,
        new FakeRequestBuilder()
            .withXForwadedFor("5.5.5.5, 6.6.6.6")
            .withRawCredentials(validRawCredentials)
            .build(),
        String.format(
            "Resolved IP 6.6.6.6 is not in allowed range for key ID: %s, which is \"%s\"",
            keyId, "2.2.2.2/30,3.3.3.3/32"));
  }

  @Test
  public void validate_logsDetailedError() {
    Logger logger = (Logger) LoggerFactory.getLogger(ApiAuthenticator.class);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    logger.addAppender(listAppender);

    var authenticator =
        new ApiAuthenticator(
            injector.getProvider(ApiKeyService.class),
            ConfigFactory.parseMap(ImmutableMap.of("client_ip_type", "FORWARDED")));

    apiKey.setSubnet("2.2.2.2/30,3.3.3.3/32");
    apiKey.save();

    assertThatThrownBy(
            () ->
                authenticator.validate(
                    new UsernamePasswordCredentials(keyId, secret),
                    new PlayWebContext(
                        new FakeRequestBuilder()
                            .withRemoteAddress("7.7.7.7")
                            .withXForwadedFor("5.5.5.5, 6.6.6.6")
                            .withRawCredentials(validRawCredentials)
                            .build()),
                    MOCK_SESSION_STORE))
        .isInstanceOf(BadCredentialsException.class);

    ImmutableList<ILoggingEvent> logsList = ImmutableList.copyOf(listAppender.list);
    assertThat(logsList.get(0).getMessage())
        .isEqualTo(
            "UnauthorizedApiRequest(resource: \"/\", Remote Address: \"7.7.7.7\","
                + " X-Forwarded-For: \"5.5.5.5, 6.6.6.6\", CLIENT_IP_TYPE: \"FORWARDED\", cause:"
                + " \"Resolved IP 6.6.6.6 is not in allowed range for key ID: keyId, which is"
                + " \"2.2.2.2/30,3.3.3.3/32\"\")");
  }

  @Test
  public void validate_invalidSecret() {
    var rawCredentials = keyId + ":" + "notthesecret";

    assertBadCredentialsException(
        new FakeRequestBuilder().withRawCredentials(rawCredentials).build(),
        new UsernamePasswordCredentials(keyId, "notthesecret"),
        "Invalid secret for key ID: " + keyId);
  }

  private void assertBadCredentialsException(Http.Request request, String expectedMessage) {
    assertBadCredentialsException(
        request, new UsernamePasswordCredentials(keyId, secret), expectedMessage);
  }

  private void assertBadCredentialsException(
      ApiAuthenticator apiAuthenticator, Http.Request request, String expectedMessage) {
    assertBadCredentialsException(
        apiAuthenticator, request, new UsernamePasswordCredentials(keyId, secret), expectedMessage);
  }

  private void assertBadCredentialsException(
      Http.Request request, UsernamePasswordCredentials credentials, String expectedMessage) {
    assertBadCredentialsException(apiAuthenticator, request, credentials, expectedMessage);
  }

  private void assertBadCredentialsException(
      ApiAuthenticator apiAuthenticator,
      Http.Request request,
      UsernamePasswordCredentials credentials,
      String expectedMessage) {
    assertThatThrownBy(
            () ->
                apiAuthenticator.validate(
                    credentials, new PlayWebContext(request), MOCK_SESSION_STORE))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage(expectedMessage);
  }

  private static class FakeRequestBuilder {
    String remoteAddress = "1.1.1.1";
    Optional<String> xForwardedFor = Optional.empty();
    Optional<String> rawCredentials = Optional.empty();

    public FakeRequestBuilder() {}

    public FakeRequestBuilder withRemoteAddress(String remoteAddress) {
      this.remoteAddress = remoteAddress;
      return this;
    }

    public FakeRequestBuilder withXForwadedFor(String xForwadedFor) {
      this.xForwardedFor = Optional.of(xForwadedFor);
      return this;
    }

    public FakeRequestBuilder withRawCredentials(String rawCredentials) {
      this.rawCredentials = Optional.of(rawCredentials);
      return this;
    }

    public Http.Request build() {
      Http.RequestBuilder fakeRequest = fakeRequest().remoteAddress(this.remoteAddress);
      xForwardedFor.ifPresent(
          xForwardedFor -> fakeRequest.header("X-Forwarded-For", xForwardedFor));
      rawCredentials
          .map(
              rawCreds ->
                  Base64.getEncoder().encodeToString(rawCreds.getBytes(StandardCharsets.UTF_8)))
          .ifPresent(creds -> fakeRequest.header("Authorization", "Basic " + creds));
      return fakeRequest.build();
    }
  }
}
