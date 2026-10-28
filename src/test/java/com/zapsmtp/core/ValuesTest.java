package com.zapsmtp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JavaType;
import com.zapsmtp.RequestOptions;
import com.zapsmtp.ZapSMTP;
import com.zapsmtp.models.Attachment;
import com.zapsmtp.models.Email;
import com.zapsmtp.models.RenderedTemplate;
import com.zapsmtp.params.TemplateRef;
import com.zapsmtp.params.TemplatesCreateParams;
import com.zapsmtp.services.EmailsAttachmentsService;
import com.zapsmtp.testing.TestServer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Numbers are never invented, and requests and responses cannot change after the fact (Codex review). */
class ValuesTest {
  private static <T> T decode(String json, Class<T> type) {
    return Json.decode(Json.parse(json), Json.MAPPER.constructType(type));
  }

  @Test
  void aFractionalNumberForAnIntegerFieldIsNullNotTruncated() {
    Attachment a = decode("{\"id\":\"a1\",\"size\":1.9}", Attachment.class);
    assertEquals("a1", a.getId());
    assertNull(a.getSize());
    assertNull(decode("{\"size\":2.0}", Attachment.class).getSize());
    assertEquals(Long.valueOf(12), decode("{\"size\":\"12\"}", Attachment.class).getSize());
    assertEquals(Long.valueOf(7), decode("{\"size\":7}", Attachment.class).getSize());
  }

  @Test
  void anOverflowingNumberIsNullAndItsNeighboursAreKept() {
    JavaType list = Json.MAPPER.getTypeFactory().constructCollectionType(List.class, Attachment.class);
    List<Attachment> all = Json.decode(Json.parse(
        "[{\"id\":\"a1\",\"size\":10},{\"id\":\"a2\",\"size\":9223372036854775808}]"), list);
    assertEquals(2, all.size());
    assertEquals(Long.valueOf(10), all.get(0).getSize());
    assertEquals("a2", all.get(1).getId());
    assertNull(all.get(1).getSize());
  }

  @Test
  void aPageKeepsTheItemsThatDecodeNextToOneThatDoesNot() throws Exception {
    try (TestServer server = new TestServer(TestServer.json(200,
        "{\"object\":\"list\",\"data\":[{\"id\":\"a1\",\"size\":10},[1,2],\"x\",{\"id\":\"a3\",\"size\":1e999}]}"))) {
      ApiClient c = new ApiClient(ZapSMTP.builder().apiKey("k").baseUrl(server.url()).build().options());
      List<Attachment> data = new EmailsAttachmentsService(c).list("e1", RequestOptions.none()).getData();
      assertEquals(4, data.size());
      assertEquals("a1", data.get(0).getId());
      assertEquals("a3", data.get(3).getId());
    }
  }

  @Test
  void aBuiltRequestSnapshotsNestedValues() {
    List<String> recipients = new ArrayList<>(List.of("original@example.org"));
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("recipients", recipients);
    vars.put("tags", new LinkedHashSet<>(List.of("a")));
    TemplateRef t = TemplateRef.builder().id("welcome").variables(vars).build();
    String before = new String(Json.write(t), StandardCharsets.UTF_8);
    recipients.set(0, "changed@example.org");
    vars.put("extra", 1);
    assertEquals(before, new String(Json.write(t), StandardCharsets.UTF_8));
    assertEquals(List.of("a"), t.getVariables().get("tags"));
    List<?> got = (List<?>) t.getVariables().get("recipients");
    assertThrows(UnsupportedOperationException.class, got::clear);

    List<Object> designRows = new ArrayList<>(List.of(1));
    Map<String, Object> design = new LinkedHashMap<>(Map.of("rows", designRows));
    TemplatesCreateParams p = TemplatesCreateParams.builder().name("n").design(design).build();
    designRows.add(2);
    assertEquals(List.of(1), ((Map<?, ?>) p.getDesign()).get("rows"));
    assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) p.getDesign()).clear());
  }

  @Test
  void anEmptyIdempotencyKeyIsRefusedBeforeSending() throws Exception {
    try (TestServer server = new TestServer(TestServer.json(200, "{\"id\":\"e1\"}"))) {
      ApiClient c = new ApiClient(ZapSMTP.builder().apiKey("k").baseUrl(server.url()).build().options());
      com.zapsmtp.services.EmailsService emails = new com.zapsmtp.services.EmailsService(c);
      com.zapsmtp.params.SendEmailParams p = com.zapsmtp.params.SendEmailParams.builder().to("a@b.c").build();
      for (RequestOptions o : List.of(RequestOptions.builder().idempotencyKey("").build(),
          RequestOptions.builder().idempotencyKey(" \t").build(), RequestOptions.builder().header("idempotency-key", " ").build(),
          RequestOptions.builder().idempotencyKey("k1").header("IDEMPOTENCY-KEY", "").build())) {
        com.zapsmtp.errors.ValidationException e =
            assertThrows(com.zapsmtp.errors.ValidationException.class, () -> emails.send(p, o));
        assertEquals("idempotencyKey must not be empty", e.getMessage());
        assertEquals("invalid_parameter", e.getCode());
      }
      assertEquals(0, server.count());
      emails.send(p, RequestOptions.builder().header("IDEMPOTENCY-KEY", "k2").build());
      assertEquals(List.of("k2"), server.last().headers.get("Idempotency-key"));
      // an operation without one sends none, and ignores the option
      new com.zapsmtp.services.DomainsService(c).get("d1", RequestOptions.builder().idempotencyKey("").build());
      assertNull(server.last().header("Idempotency-Key"));
    }
  }

  @Test
  void aNonFiniteNumberIsNull() {
    for (String v : List.of("1e999", "-1e999", "\"NaN\"", "\"Infinity\"", "\"\"", "\"abc\"")) {
      com.zapsmtp.models.MetricsTotals t = decode("{\"delivery_rate\":" + v + ",\"future\":" + v + "}",
          com.zapsmtp.models.MetricsTotals.class);
      assertNull(t.getDeliveryRate(), v);
    }
    com.zapsmtp.models.MetricsTotals t = decode("{\"delivery_rate\":0.5,\"future\":[1e999,2.5]}", com.zapsmtp.models.MetricsTotals.class);
    assertEquals(Double.valueOf(0.5), t.getDeliveryRate());
    assertEquals(java.util.Arrays.asList(null, 2.5), t.getAdditionalProperties().get("future"));
    assertNull(decode("{\"future\":1e999}", Email.class).getAdditionalProperties().get("future"));
  }

  @Test
  void anAbsurdRetryAfterNeverThrows() {
    for (String v : List.of("1e400", "-1e400", "99999999999999999999999", "Fri, 31 Dec 9999 23:59:59 GMT",
        "Mon, 01 Jan 0001 00:00:00 GMT", "Thu, 01 Jan 99999 00:00:00 GMT", "\u0000", "NaN")) {
      java.net.http.HttpHeaders h = java.net.http.HttpHeaders.of(Map.of("Retry-After", List.of(v)), (a, b) -> true);
      java.time.Duration d = new com.zapsmtp.errors.RateLimitException("x", 429, "", "", null, h, null).getRetryAfter();
      if (d != null) {
        assertEquals(false, d.isNegative(), v);
        assertEquals(true, Retry.backoff(0, d).toMillis() <= 60_000, v);
      }
    }
  }

  @Test
  void responseDataIsDeeplyImmutable() {
    Email e = decode("{\"id\":\"e1\",\"future\":{\"items\":[1,2]},\"metadata\":{\"a\":\"b\"}}", Email.class);
    Map<?, ?> future = (Map<?, ?>) e.getAdditionalProperties().get("future");
    assertThrows(UnsupportedOperationException.class, ((List<?>) future.get("items"))::clear);
    assertThrows(UnsupportedOperationException.class, future::clear);
    assertThrows(UnsupportedOperationException.class, () -> e.getMetadata().clear());
    RenderedTemplate r = decode("{\"design\":{\"rows\":[{\"a\":1}]}}", RenderedTemplate.class);
    assertNotNull(r.getDesign());
    List<?> rows = (List<?>) ((Map<?, ?>) r.getDesign()).get("rows");
    assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) rows.get(0)).clear());
    assertEquals(e, decode("{\"id\":\"e1\",\"future\":{\"items\":[1,2]},\"metadata\":{\"a\":\"b\"}}", Email.class));
  }
}
