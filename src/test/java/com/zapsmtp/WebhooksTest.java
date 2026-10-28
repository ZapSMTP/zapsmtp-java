package com.zapsmtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.zapsmtp.core.Json;
import com.zapsmtp.errors.WebhookVerificationException;
import com.zapsmtp.models.Email;
import com.zapsmtp.testing.Cases;
import com.zapsmtp.testing.JsonAssert;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class WebhooksTest {
  private static final JsonNode VECTORS = Cases.read("spec/webhook-vectors.json").path("vectors");
  private static final JsonNode FIRST = VECTORS.get(0);

  private static Clock at(long seconds) {
    return Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC);
  }

  private static String sign(String secret, long t, String payload) {
    Mac mac = Webhooks.hmac("HmacSHA256", secret.getBytes(StandardCharsets.UTF_8));
    byte[] sig = mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : sig) {
      hex.append(String.format("%02x", b));
    }
    return "t=" + t + ",v1=" + hex;
  }

  @TestFactory
  Stream<DynamicTest> vectors() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode v : VECTORS) {
      tests.add(DynamicTest.dynamicTest(v.path("name").asText(), () -> {
        Map<String, String> headers = Map.of("Zap-Signature", v.path("header").asText());
        String payload = v.path("payload").asText();
        Clock clock = at(v.path("now").asLong());
        Duration tolerance = Duration.ofSeconds(v.path("tolerance").asLong());
        String secret = v.path("secret").asText();
        String sig = Webhooks.signatureHeader(headers);
        if (!v.path("valid").asBoolean()) {
          assertThrows(WebhookVerificationException.class, () -> Webhooks.verify(payload, sig, secret, tolerance, clock));
          assertThrows(WebhookVerificationException.class, () -> Webhooks.verifyEvents(payload, sig, secret, tolerance, clock));
        } else if (v.path("batch").asBoolean()) {
          WebhookVerificationException e = assertThrows(WebhookVerificationException.class,
              () -> Webhooks.verify(payload, sig, secret, tolerance, clock));
          assertEquals("The body is a batch of events: use verifyEvents", e.getMessage());
          List<WebhookEvent> all = Webhooks.verifyEvents(payload, sig, secret, tolerance, clock);
          JsonAssert.assertJsonEquals(Json.MAPPER.readTree(payload), Json.MAPPER.valueToTree(all), "events");
        } else {
          WebhookEvent e = Webhooks.verify(payload, sig, secret, tolerance, clock);
          JsonAssert.assertJsonEquals(Json.MAPPER.readTree(payload), Json.MAPPER.valueToTree(e), "event");
          List<WebhookEvent> all = Webhooks.verifyEvents(payload, sig, secret, tolerance, clock);
          assertEquals(List.of(e), all);
        }
      }));
    }
    assertTrue(tests.size() >= 15);
    return tests.stream();
  }

  @Test
  void takesHeadersAHeaderValueBytesAndTheClientMethods() {
    String payload = FIRST.path("payload").asText();
    String header = FIRST.path("header").asText();
    String secret = FIRST.path("secret").asText();
    Clock clock = at(FIRST.path("now").asLong());
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    WebhookEvent e = Webhooks.verify(bytes, header, secret, Webhooks.DEFAULT_TOLERANCE, clock);
    assertEquals("evt_1", e.getId());
    assertEquals("email.delivered", e.getType());
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), e.getCreatedAt().toInstant());
    assertEquals("jane@example.org", e.getData().get("to"));
    assertEquals("Привет ✓", e.getData().get("subject"));
    assertEquals(List.of("jane@example.org"), e.getData(Email.class).getTo());
    Map<String, Object> multi = new HashMap<>();
    multi.put("content-type", "application/json");
    multi.put("zap-signature", List.of(header));
    assertEquals(header, Webhooks.signatureHeader(multi));
    Map<String, Object> nulls = new HashMap<>();
    nulls.put("Zap-Signature", null);
    assertNull(Webhooks.signatureHeader(nulls));
    assertNull(Webhooks.signatureHeader(Map.of("Content-Type", "application/json")));

    ZapSMTP zap = ZapSMTP.builder().apiKey("k").build();
    assertEquals("evt_1", zap.webhooks().verify(payload, header, secret, Webhooks.DEFAULT_TOLERANCE, clock).getId());
    assertEquals("evt_1", zap.webhooks().verify(bytes, header, secret, Webhooks.DEFAULT_TOLERANCE, clock).getId());
    // the overloads without a clock check against now
    long now = Instant.now().getEpochSecond();
    String fresh = sign(secret, now, payload);
    assertEquals("evt_1", Webhooks.verify(payload, Map.of("Zap-Signature", fresh), secret).getId());
    assertEquals("evt_1", Webhooks.verify(payload, fresh, secret).getId());
    assertEquals("evt_1", Webhooks.verify(bytes, Map.of("zap-signature", fresh), secret).getId());
    assertEquals("evt_1", Webhooks.verify(bytes, fresh, secret).getId());
    assertEquals("evt_1", zap.webhooks().verify(payload, Map.of("Zap-Signature", fresh), secret).getId());
    assertEquals("evt_1", zap.webhooks().verify(payload, fresh, secret).getId());
    assertEquals("evt_1", zap.webhooks().verify(bytes, Map.of("Zap-Signature", fresh), secret).getId());
    assertEquals("evt_1", zap.webhooks().verify(bytes, fresh, secret).getId());
    assertEquals("evt_1", Webhooks.verifyEvents(payload, Map.of("Zap-Signature", fresh), secret).get(0).getId());
    assertEquals("evt_1", Webhooks.verifyEvents(bytes, Map.of("Zap-Signature", fresh), secret).get(0).getId());
    assertEquals("evt_1", Webhooks.verifyEvents(bytes, fresh, secret).get(0).getId());
    assertEquals("evt_1", zap.webhooks().verifyEvents(payload, Map.of("Zap-Signature", fresh), secret).get(0).getId());
    assertEquals("evt_1", zap.webhooks().verifyEvents(payload, fresh, secret).get(0).getId());
    assertEquals("evt_1", zap.webhooks().verifyEvents(bytes, Map.of("Zap-Signature", fresh), secret).get(0).getId());
    assertEquals("evt_1", zap.webhooks().verifyEvents(bytes, fresh, secret).get(0).getId());
    assertEquals("evt_1", zap.webhooks().verifyEvents(payload, header, secret, Webhooks.DEFAULT_TOLERANCE, clock).get(0).getId());
    assertEquals("evt_1", zap.webhooks().verifyEvents(bytes, header, secret, Webhooks.DEFAULT_TOLERANCE, clock).get(0).getId());
  }

  @Test
  void checksAgainstTheClockByDefault() {
    String payload = FIRST.path("payload").asText();
    String secret = FIRST.path("secret").asText();
    long now = Instant.now().getEpochSecond();
    WebhookVerificationException e = assertThrows(WebhookVerificationException.class,
        () -> Webhooks.verify(payload, sign(secret, now - 301, payload), secret));
    assertTrue(e.getMessage().contains("too old"));
  }

  @Test
  void refusesAMissingHeaderOrSecretAndABodyThatIsNotAnEvent() {
    long t = 1700000000;
    Clock clock = at(t);
    Duration tol = Webhooks.DEFAULT_TOLERANCE;
    assertEquals("Missing Zap-Signature header",
        assertThrows(WebhookVerificationException.class, () -> Webhooks.verify("{}", Map.of(), "s")).getMessage());
    assertEquals("Missing Zap-Signature header",
        assertThrows(WebhookVerificationException.class, () -> Webhooks.verify("{}", "", "s")).getMessage());
    assertEquals("Missing webhook secret",
        assertThrows(WebhookVerificationException.class, () -> Webhooks.verify("{}", "t=1,v1=00", "")).getMessage());
    assertEquals("Missing webhook secret",
        assertThrows(WebhookVerificationException.class, () -> Webhooks.verify("{}", "t=1,v1=00", (String) null)).getMessage());
    WebhookVerificationException e = assertThrows(WebhookVerificationException.class,
        () -> Webhooks.verify("not json", sign("whsec_x", t, "not json"), "whsec_x", tol, clock));
    assertEquals("Webhook body is not JSON", e.getMessage());
    assertTrue(e.getCause() != null);
    for (String body : List.of("42", "null", "[1]", "[[]]")) {
      assertEquals("Webhook body is not an event", assertThrows(WebhookVerificationException.class,
          () -> Webhooks.verifyEvents(body, sign("whsec_x", t, body), "whsec_x", tol, clock)).getMessage(), body);
      String one = assertThrows(WebhookVerificationException.class,
          () -> Webhooks.verify(body, sign("whsec_x", t, body), "whsec_x", tol, clock)).getMessage();
      assertEquals(body.startsWith("[") ? "The body is a batch of events: use verifyEvents" : "Webhook body is not an event", one);
    }
    for (String body : List.of("", "{} x")) {
      assertEquals("Webhook body is not JSON", assertThrows(WebhookVerificationException.class,
          () -> Webhooks.verify(body, sign("whsec_x", t, body), "whsec_x", tol, clock)).getMessage());
    }
    assertEquals(List.of(), Webhooks.verifyEvents("[]", sign("whsec_x", t, "[]"), "whsec_x", tol, clock));
    assertEquals("evt", Webhooks.verify("{\"id\":\"evt\"}", "v0=abc," + sign("whsec_x", t, "{\"id\":\"evt\"}"), "whsec_x", tol, clock).getId());
    // parts without "=" are skipped; a timestamp longer than 12 digits is malformed
    assertEquals("evt", Webhooks.verify("{\"id\":\"evt\"}", "junk," + sign("whsec_x", t, "{\"id\":\"evt\"}"), "whsec_x", tol, clock).getId());
    assertEquals("Malformed Zap-Signature header", assertThrows(WebhookVerificationException.class,
        () -> Webhooks.verify("{}", "t=99999999999999999999,v1=00", "whsec_x", tol, clock)).getMessage());
  }

  @Test
  void needsHmacSha256() {
    assertThrows(IllegalStateException.class, () -> Webhooks.hmac("HmacNope", new byte[] {1}));
  }
}
