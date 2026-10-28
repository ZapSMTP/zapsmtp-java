package com.zapsmtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zapsmtp.errors.ZapSMTPException;
import com.zapsmtp.testing.TestServer;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientTest {
  @Test
  void readsTheKeyAndBaseUrlFromTheEnvironment() throws Exception {
    try (TestServer server = new TestServer(TestServer.json(200, "{\"id\":\"e1\"}"))) {
      Map<String, String> env = Map.of("ZAP_API_KEY", "zap_env", "ZAP_BASE_URL", server.url() + "//");
      ZapSMTP zap = ZapSMTP.builder().env(env::get).build();
      assertEquals("zap_env", zap.options().getApiKey());
      assertEquals(server.url(), zap.options().getBaseUrl());
      zap.emails().get("e1");
      assertEquals("/v1/emails/e1", server.last().path);
      assertEquals("Bearer zap_env", server.last().header("Authorization"));
    }
  }

  @Test
  void argumentsWinOverTheEnvironment() {
    Map<String, String> env = Map.of("ZAP_API_KEY", "zap_env", "ZAP_BASE_URL", "https://env.test/v1");
    ClientOptions o = ZapSMTP.builder().env(env::get).apiKey("zap_arg").baseUrl("https://arg.test/v1/").build().options();
    assertEquals("zap_arg", o.getApiKey());
    assertEquals("https://arg.test/v1", o.getBaseUrl());
  }

  @Test
  void defaultsToTheProductionApi() {
    ClientOptions o = ZapSMTP.builder().env(Map.of("ZAP_BASE_URL", " ")::get).apiKey("k").build().options();
    assertEquals("https://api.zapsmtp.com/v1", o.getBaseUrl());
    assertEquals(Duration.ofSeconds(60), o.getTimeout());
    assertEquals(2, o.getMaxRetries());
    assertNull(o.getHttpClient());
    assertEquals(Map.of(), o.getDefaultHeaders());
    assertEquals("https://api.zapsmtp.com/v1", ZapSMTP.builder().env(Map.<String, String>of()::get).apiKey("k").build().options().getBaseUrl());
  }

  @Test
  void needsAKey() {
    ZapSMTPException e = assertThrows(ZapSMTPException.class, () -> ZapSMTP.builder().env(Map.<String, String>of()::get).build());
    assertTrue(e.getMessage().startsWith("Missing API key"));
    assertEquals(0, e.getStatusCode());
    assertThrows(ZapSMTPException.class, () -> ZapSMTP.builder().env(Map.of("ZAP_API_KEY", "")::get).build());
    assertThrows(ZapSMTPException.class, () -> ZapSMTP.builder().apiKey(" ").build());
  }

  @Test
  void fromEnvReadsTheEnvironment() {
    // pom.xml sets ZAP_API_KEY for the tests
    assertEquals("zap_test_from_env", ZapSMTP.fromEnv().options().getApiKey());
  }

  @Test
  void checksItsSettings() {
    ZapSMTP.Builder b = ZapSMTP.builder();
    assertThrows(IllegalArgumentException.class, () -> b.timeout(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> b.timeout(Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> b.maxRetries(-1));
    assertThrows(NullPointerException.class, () -> b.httpClient(null));
    assertThrows(NullPointerException.class, () -> b.defaultHeader(null, "v"));
    assertThrows(NullPointerException.class, () -> b.defaultHeader("n", null));
    ClientOptions o = b.apiKey("k").timeout(Duration.ofSeconds(5)).maxRetries(0).defaultHeader("X-A", "1").build().options();
    assertEquals(Duration.ofSeconds(5), o.getTimeout());
    assertEquals(0, o.getMaxRetries());
    assertEquals(Map.of("X-A", "1"), o.getDefaultHeaders());
  }

  @Test
  void versionMatchesThePom() {
    assertEquals(System.getProperty("zapsmtp.version"), Version.VERSION);
  }
}
