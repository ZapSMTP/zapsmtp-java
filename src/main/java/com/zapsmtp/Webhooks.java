package com.zapsmtp;

import com.fasterxml.jackson.databind.JsonNode;
import com.zapsmtp.core.Json;
import com.zapsmtp.errors.WebhookVerificationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signature verification. Every request ZapSMTP sends carries
 * {@code Zap-Signature: t=<unix seconds>,v1=<hex>}, where v1 is HMAC-SHA256 over {@code "<t>.<raw body>"} keyed
 * with the whole signing secret (its {@code whsec_} prefix included). For 24 hours after a secret rotation two v1
 * entries are sent; either one matching is enough.
 *
 * <p>Pass the body exactly as received, before any JSON parsing.
 *
 * <pre>{@code
 * WebhookEvent event = Webhooks.verify(body, request.getHeader("Zap-Signature"), secret);
 * List<WebhookEvent> events = Webhooks.verifyEvents(body, request.getHeader("Zap-Signature"), secret); // batch delivery
 * }</pre>
 */
public final class Webhooks {
  /** The header that carries the signature. */
  public static final String SIGNATURE_HEADER = "Zap-Signature";
  /** The oldest signature accepted unless set: 5 minutes, either way. */
  public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

  private static final Pattern DIGITS = Pattern.compile("\\d{1,12}"); // unix seconds
  private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

  private Webhooks() {}

  /**
   * Checks a webhook request and returns its event.
   *
   * @param payload the raw body
   * @param headers the request headers (names in any case; a value may be a string or a list of strings)
   * @param secret the endpoint's signing secret, whsec_...
   * @return the event
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not one
   *     event (a webhook with batch delivery sends a list: use {@link #verifyEvents(String, Map, String)})
   */
  public static WebhookEvent verify(String payload, Map<String, ?> headers, String secret) {
    return verify(payload, signatureHeader(headers), secret);
  }

  /**
   * Checks a webhook request and returns its event.
   *
   * @param payload the raw body
   * @param signature the Zap-Signature header
   * @param secret the endpoint's signing secret, whsec_...
   * @return the event
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not one
   *     event
   */
  public static WebhookEvent verify(String payload, String signature, String secret) {
    return verify(payload, signature, secret, DEFAULT_TOLERANCE, Clock.systemUTC());
  }

  /**
   * Checks a webhook request and returns its event.
   *
   * @param payload the raw body
   * @param headers the request headers (names in any case; a value may be a string or a list of strings)
   * @param secret the endpoint's signing secret, whsec_...
   * @return the event
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not one
   *     event
   */
  public static WebhookEvent verify(byte[] payload, Map<String, ?> headers, String secret) {
    return verify(payload, signatureHeader(headers), secret);
  }

  /**
   * Checks a webhook request and returns its event.
   *
   * @param payload the raw body
   * @param signature the Zap-Signature header
   * @param secret the endpoint's signing secret, whsec_...
   * @return the event
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not one
   *     event
   */
  public static WebhookEvent verify(byte[] payload, String signature, String secret) {
    return verify(payload, signature, secret, DEFAULT_TOLERANCE, Clock.systemUTC());
  }

  /**
   * Checks a webhook request against a tolerance and a clock, and returns its event.
   *
   * @param payload the raw body, read as UTF-8
   * @param signature the Zap-Signature header
   * @param secret the endpoint's signing secret, whsec_...
   * @param tolerance the oldest signature accepted, either way
   * @param clock the time to check against
   * @return the event
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not one
   *     event
   */
  public static WebhookEvent verify(String payload, String signature, String secret, Duration tolerance,
      Clock clock) {
    return verify(payload.getBytes(StandardCharsets.UTF_8), signature, secret, tolerance, clock);
  }

  /**
   * Checks a webhook request against a tolerance and a clock, and returns its event.
   *
   * @param payload the raw body
   * @param signature the Zap-Signature header
   * @param secret the endpoint's signing secret, whsec_...
   * @param tolerance the oldest signature accepted, either way
   * @param clock the time to check against
   * @return the event
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not one
   *     event
   */
  public static WebhookEvent verify(byte[] payload, String signature, String secret, Duration tolerance,
      Clock clock) {
    JsonNode body = verified(payload, signature, secret, tolerance, clock);
    if (body.isArray()) {
      throw new WebhookVerificationException("The body is a batch of events: use verifyEvents", null);
    }
    return event(body);
  }

  /**
   * Checks a webhook request and returns its events: every event of a batch (a webhook with batch delivery), or
   * the one event of any other request.
   *
   * @param payload the raw body
   * @param headers the request headers (names in any case; a value may be a string or a list of strings)
   * @param secret the endpoint's signing secret, whsec_...
   * @return the events, in the order sent
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not
   *     events
   */
  public static List<WebhookEvent> verifyEvents(String payload, Map<String, ?> headers, String secret) {
    return verifyEvents(payload, signatureHeader(headers), secret);
  }

  /**
   * Checks a webhook request and returns its events: every event of a batch, or the one event.
   *
   * @param payload the raw body
   * @param signature the Zap-Signature header
   * @param secret the endpoint's signing secret, whsec_...
   * @return the events, in the order sent
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not
   *     events
   */
  public static List<WebhookEvent> verifyEvents(String payload, String signature, String secret) {
    return verifyEvents(payload, signature, secret, DEFAULT_TOLERANCE, Clock.systemUTC());
  }

  /**
   * Checks a webhook request and returns its events: every event of a batch, or the one event.
   *
   * @param payload the raw body
   * @param headers the request headers (names in any case; a value may be a string or a list of strings)
   * @param secret the endpoint's signing secret, whsec_...
   * @return the events, in the order sent
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not
   *     events
   */
  public static List<WebhookEvent> verifyEvents(byte[] payload, Map<String, ?> headers, String secret) {
    return verifyEvents(payload, signatureHeader(headers), secret);
  }

  /**
   * Checks a webhook request and returns its events: every event of a batch, or the one event.
   *
   * @param payload the raw body
   * @param signature the Zap-Signature header
   * @param secret the endpoint's signing secret, whsec_...
   * @return the events, in the order sent
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not
   *     events
   */
  public static List<WebhookEvent> verifyEvents(byte[] payload, String signature, String secret) {
    return verifyEvents(payload, signature, secret, DEFAULT_TOLERANCE, Clock.systemUTC());
  }

  /**
   * Checks a webhook request against a tolerance and a clock, and returns its events.
   *
   * @param payload the raw body, read as UTF-8
   * @param signature the Zap-Signature header
   * @param secret the endpoint's signing secret, whsec_...
   * @param tolerance the oldest signature accepted, either way
   * @param clock the time to check against
   * @return the events, in the order sent
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not
   *     events
   */
  public static List<WebhookEvent> verifyEvents(String payload, String signature, String secret, Duration tolerance,
      Clock clock) {
    return verifyEvents(payload.getBytes(StandardCharsets.UTF_8), signature, secret, tolerance, clock);
  }

  /**
   * Checks a webhook request against a tolerance and a clock, and returns its events.
   *
   * @param payload the raw body
   * @param signature the Zap-Signature header
   * @param secret the endpoint's signing secret, whsec_...
   * @param tolerance the oldest signature accepted, either way
   * @param clock the time to check against
   * @return the events, in the order sent
   * @throws WebhookVerificationException when the signature is missing, wrong or too old, or the body is not
   *     events
   */
  public static List<WebhookEvent> verifyEvents(byte[] payload, String signature, String secret, Duration tolerance,
      Clock clock) {
    JsonNode body = verified(payload, signature, secret, tolerance, clock);
    List<WebhookEvent> events = new ArrayList<>();
    if (body.isArray()) {
      body.forEach((e) -> events.add(event(e)));
    } else {
      events.add(event(body));
    }
    return Collections.unmodifiableList(events);
  }

  private static WebhookEvent event(JsonNode node) {
    if (!node.isObject()) {
      throw new WebhookVerificationException("Webhook body is not an event", null);
    }
    return Json.MAPPER.convertValue(node, WebhookEvent.class);
  }

  // checks the signature and parses the body
  private static JsonNode verified(byte[] payload, String signature, String secret, Duration tolerance, Clock clock) {
    if (signature == null || signature.isEmpty()) {
      throw new WebhookVerificationException("Missing Zap-Signature header", null);
    }
    if (secret == null || secret.isEmpty()) {
      throw new WebhookVerificationException("Missing webhook secret", null);
    }
    String t = "";
    List<String> v1 = new ArrayList<>();
    for (String part : signature.split(",")) {
      int i = part.indexOf('=');
      if (i < 0) {
        continue;
      }
      String k = part.substring(0, i).trim();
      String v = part.substring(i + 1).trim();
      if (k.equals("t")) {
        t = v;
      } else if (k.equals("v1")) {
        v1.add(v);
      }
    }
    if (!DIGITS.matcher(t).matches() || v1.isEmpty()) {
      throw new WebhookVerificationException("Malformed Zap-Signature header", null);
    }
    long age = Math.abs(clock.instant().getEpochSecond() - Long.parseLong(t));
    if (Duration.ofSeconds(age).compareTo(tolerance) > 0) {
      throw new WebhookVerificationException("Webhook signature is too old (or from the future)", null);
    }
    Mac mac = hmac("HmacSHA256", secret.getBytes(StandardCharsets.UTF_8));
    mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
    byte[] want = mac.doFinal(payload);
    boolean ok = false;
    for (String hex : v1) {
      // every entry is compared, in constant time
      ok |= HEX64.matcher(hex).matches() && MessageDigest.isEqual(unhex(hex), want);
    }
    if (!ok) {
      throw new WebhookVerificationException("Webhook signature does not match", null);
    }
    JsonNode json;
    try {
      json = Json.MAPPER.readTree(payload);
    } catch (IOException e) {
      throw new WebhookVerificationException("Webhook body is not JSON", e);
    }
    if (json.isMissingNode()) {
      throw new WebhookVerificationException("Webhook body is not JSON", null);
    }
    return json;
  }

  /**
   * Finds the Zap-Signature header in a map of request headers.
   *
   * @param headers the headers (names in any case; a value may be a string or a collection, joined with commas)
   * @return the value, or null
   */
  public static String signatureHeader(Map<String, ?> headers) {
    for (Map.Entry<String, ?> e : headers.entrySet()) {
      if (SIGNATURE_HEADER.equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
        Object v = e.getValue();
        return v instanceof Collection
            ? ((Collection<?>) v).stream().map(String::valueOf).collect(Collectors.joining(","))
            : v.toString();
      }
    }
    return null;
  }

  static Mac hmac(String algorithm, byte[] key) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(key, algorithm));
      return mac;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(algorithm + " is not available", e);
    }
  }

  private static byte[] unhex(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }
}
