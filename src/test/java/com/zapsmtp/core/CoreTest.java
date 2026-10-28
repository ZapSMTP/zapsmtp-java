package com.zapsmtp.core;

import static com.zapsmtp.testing.TestServer.apiError;
import static com.zapsmtp.testing.TestServer.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zapsmtp.ClientOptions;
import com.zapsmtp.Page;
import com.zapsmtp.RequestOptions;
import com.zapsmtp.Version;
import com.zapsmtp.ZapResponse;
import com.zapsmtp.ZapSMTP;
import com.zapsmtp.errors.AuthenticationException;
import com.zapsmtp.errors.ConflictException;
import com.zapsmtp.errors.ConnectionException;
import com.zapsmtp.errors.NotFoundException;
import com.zapsmtp.errors.PermissionException;
import com.zapsmtp.errors.RateLimitException;
import com.zapsmtp.errors.ServerException;
import com.zapsmtp.errors.ValidationException;
import com.zapsmtp.errors.ZapSMTPException;
import com.zapsmtp.models.Domain;
import com.zapsmtp.models.Email;
import com.zapsmtp.models.Log;
import com.zapsmtp.models.SendEmailResponse;
import com.zapsmtp.models.Suppression;
import com.zapsmtp.params.DomainsCreateParams;
import com.zapsmtp.params.EmailsListParams;
import com.zapsmtp.params.EmailsReceivingAttachmentsListParams;
import com.zapsmtp.params.LogsListParams;
import com.zapsmtp.params.SendBatchOptions;
import com.zapsmtp.params.SendEmailParams;
import com.zapsmtp.params.ThreadsListParams;
import com.zapsmtp.services.DomainsService;
import com.zapsmtp.services.EmailsReceivingAttachmentsService;
import com.zapsmtp.services.EmailsReceivingService;
import com.zapsmtp.services.EmailsService;
import com.zapsmtp.services.LogsService;
import com.zapsmtp.services.SuppressionsService;
import com.zapsmtp.services.ThreadsService;
import com.zapsmtp.services.UsageService;
import com.zapsmtp.testing.TestServer;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The core, through the generated services: requests, errors, retries, paging (sdks/node/test/core.test.ts). */
class CoreTest {
  private TestServer server;
  private final List<Long> sleeps = new ArrayList<>();
  private Clock clock = Clock.systemUTC();

  @AfterEach
  void close() {
    if (server != null) {
      server.close();
    }
  }

  private ApiClient client(ZapSMTP.Builder builder) {
    return new ApiClient(builder.build().options(), clock, (ms) -> sleeps.add(ms));
  }

  private ApiClient client(TestServer.Answer... answers) throws IOException {
    server = new TestServer(answers);
    return client(ZapSMTP.builder().apiKey("zap_test_key").baseUrl(server.url() + "/"));
  }

  private static Map<String, String> ordered(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  // ---- requests

  @Test
  void sendsTheHeadersEveryRequestCarries() throws IOException {
    server = new TestServer(json(200, "{\"id\":\"x\"}"));
    ApiClient c = client(ZapSMTP.builder().apiKey("zap_test_key").baseUrl(server.url()).defaultHeader("X-Team", "mail"));
    new EmailsService(c).get("a/b c!'()*~", RequestOptions.builder().header("X-Extra", "1").build());
    TestServer.Seen s = server.last();
    assertEquals("/v1/emails/a%2Fb%20c!'()*~", s.path);
    assertEquals("Bearer zap_test_key", s.header("Authorization"));
    assertEquals("application/json", s.header("Accept"));
    assertEquals("zapsmtp-java/" + Version.VERSION + " (java/" + System.getProperty("java.version") + ")", s.header("User-Agent"));
    assertEquals("mail", s.header("X-Team"));
    assertEquals("1", s.header("X-Extra"));
    assertNull(s.header("Content-Type"));
    assertNull(s.header("Idempotency-Key"));
    assertEquals("", s.text());
  }

  @Test
  void refusesAnIdThatCannotBeAPathSegmentBeforeSending() throws IOException {
    DomainsService domains = new DomainsService(client(TestServer.text(204, "")));
    String[][] table = {{"", "id must not be empty"}, {null, "id must not be empty"}, {".", "id must not be \".\""},
        {"..", "id must not be \"..\""}};
    for (String[] row : table) {
      ValidationException e = assertThrows(ValidationException.class, () -> domains.delete(row[0]));
      assertEquals(row[1], e.getMessage());
      assertEquals("invalid_parameter", e.getCode());
      assertEquals(0, e.getStatusCode());
    }
    assertEquals(0, server.count());
    domains.delete("a.b");
    domains.delete("...");
    assertEquals(List.of("/v1/domains/a.b", "/v1/domains/..."), server.seen().stream().map((s) -> s.path).collect(Collectors.toList()));
  }

  @Test
  void sendsTheEmptyListsAndMapsTheCallerSets() throws IOException {
    EmailsService emails = new EmailsService(client(json(200, "{\"id\":\"e1\"}")));
    emails.send(SendEmailParams.builder().to().cc(List.of()).metadata(Map.of()).tags().build());
    assertEquals("{\"to\":[],\"cc\":[],\"metadata\":{},\"tags\":[]}", server.last().text());
  }

  @Test
  void serializesQueryParameters() throws IOException {
    ApiClient c = client(json(200, "{\"object\":\"list\",\"data\":[],\"has_more\":false}"));
    new EmailsService(c).list(EmailsListParams.builder().limit(5).q(null).metadata(ordered("order_id", "A-1", "a b", "c")).build());
    assertEquals("/v1/emails?limit=5&metadata%5Border_id%5D=A-1&metadata%5Ba+b%5D=c", server.last().target());
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("tag", List.of("a", "b"));
    q.put("none", null);
    c.request(ApiRequest.builder("GET", "/x").build().withQuery(q), Void.class, RequestOptions.none());
    assertEquals("/v1/x?tag=a&tag=b", server.last().target());
  }

  @Test
  void picksQueryAndBodyParametersByName() throws IOException {
    ApiClient c = client(json(200, "{}"));
    LogsListParams p = LogsListParams.builder().limit(3).status("200").path("/emails").build();
    c.request(ApiRequest.builder("POST", "/x").query(p, "limit", "missing").body(p, "status", "path").build(), Void.class,
        RequestOptions.none());
    assertEquals("/v1/x?limit=3", server.last().target());
    assertEquals("{\"status\":\"200\",\"path\":\"/emails\"}", server.last().text());
    assertThrows(NullPointerException.class, () -> ApiRequest.builder("GET", "/x").query(null));
    assertThrows(NullPointerException.class, () -> ApiRequest.builder("GET", "/x").body((Object) null));
  }

  @Test
  void sendsJsonBodiesAndAnIdempotencyKeyOnSends() throws IOException {
    EmailsService emails = new EmailsService(client(json(200, "{\"id\":\"e1\"}")));
    SendEmailResponse r = emails.send(SendEmailParams.builder().from("a@x.test").to("b@y.test").subject("Hi").text("Hello").build());
    assertEquals("e1", r.getId());
    TestServer.Seen s = server.last();
    assertEquals("application/json", s.header("Content-Type"));
    assertTrue(s.header("Idempotency-Key").matches("[0-9a-f-]{36}"));
    assertEquals("{\"from\":\"a@x.test\",\"to\":[\"b@y.test\"],\"subject\":\"Hi\",\"text\":\"Hello\"}", s.text());
    emails.send(SendEmailParams.builder().to("b").build(), RequestOptions.builder().idempotencyKey("order-1").build());
    assertEquals("order-1", server.last().header("Idempotency-Key"));
    new DomainsService(client(json(201, "{\"id\":\"d1\"}"))).create(DomainsCreateParams.builder().name("a.test").build(),
        RequestOptions.builder().idempotencyKey("ignored").build());
    assertNull(server.last().header("Idempotency-Key"));
  }

  @Test
  void requiredParametersAreChecked() {
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> SendEmailParams.builder().from("a").build());
    assertEquals("to is required", e.getMessage());
    assertThrows(IllegalStateException.class, () -> SendEmailParams.builder().to((List<String>) null).build());
  }

  @Test
  void sendsNullToClearAValue() throws IOException {
    ApiClient c = client(json(200, "{}"));
    c.request(ApiRequest.builder("PATCH", "/x").body(com.zapsmtp.params.ApiKeysUpdateParams.builder().domainId(null).build()).build(),
        Void.class, RequestOptions.none());
    assertEquals("{\"domain_id\":null}", server.last().text());
  }

  @Test
  void sendsABatchWithItsValidationMode() throws IOException {
    EmailsService emails = new EmailsService(client(json(200, "{\"data\":[]}")));
    List<SendEmailParams> batch = List.of(SendEmailParams.builder().to("b").build());
    emails.sendBatch(batch);
    assertNull(server.last().header("x-batch-validation"));
    assertEquals("[{\"to\":[\"b\"]}]", server.last().text());
    emails.sendBatch(batch, SendBatchOptions.builder().validation(SendBatchOptions.Validation.PERMISSIVE).build());
    assertEquals("permissive", server.last().header("x-batch-validation"));
    assertThrows(NullPointerException.class, () -> emails.sendBatch(batch, null));
  }

  @Test
  void answersNothingFor204() throws IOException {
    DomainsService domains = new DomainsService(client(TestServer.text(204, "")));
    domains.delete("d1");
    ZapResponse<Void> r = domains.deleteWithResponse("d1");
    assertNull(r.getData());
    assertEquals(204, r.getStatusCode());
    assertEquals("", r.getRawBody());
  }

  @Test
  void keepsABodyThatIsNotJsonAsText() throws IOException {
    ZapResponse<?> r = new UsageService(client(TestServer.text(200, "plain"))).getWithResponse();
    assertNull(r.getData());
    assertEquals("plain", r.getRawBody());
    assertNull(new UsageService(client(TestServer.text(200, "   "))).get());
  }

  @Test
  void givesTheStatusHeadersAndRequestId() throws IOException {
    ZapResponse<Email> r = new EmailsService(client(json(200, "{\"id\":\"e1\"}").header("RateLimit-Remaining", "9"))).getWithResponse("e1");
    assertEquals("e1", r.getData().getId());
    assertEquals(200, r.getStatusCode());
    assertEquals("req_1", r.getRequestId());
    assertEquals("9", r.getHeader("ratelimit-remaining"));
    assertEquals(List.of("9"), r.getHeaders().allValues("RateLimit-Remaining"));
    assertNull(r.getHeader("x-missing"));
    assertEquals("{\"id\":\"e1\"}", r.getRawBody());
  }

  @Test
  void usesTheGivenHttpClient() throws IOException {
    server = new TestServer(json(200, "{}"));
    HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    ZapSMTP zap = ZapSMTP.builder().apiKey("k").baseUrl(server.url()).httpClient(http).build();
    assertEquals(http, zap.options().getHttpClient());
    zap.usage().get();
    assertEquals(1, server.count());
  }

  // ---- errors

  @ParameterizedTest
  @CsvSource({
    "400, validation_error, ValidationException", "401, invalid_api_key, AuthenticationException",
    "403, restricted_api_key, PermissionException", "404, not_found, NotFoundException", "409, conflict, ConflictException",
    "413, payload_too_large, ValidationException", "422, invalid_parameter, ValidationException",
    "429, monthly_quota_exceeded, RateLimitException", "500, internal_error, ServerException",
    "410, link_expired, ZapSMTPException"})
  void mapsErrors(int status, String code, String cls) throws IOException {
    EmailsService emails = new EmailsService(client(apiError(status, code)));
    ZapSMTPException e = assertThrows(ZapSMTPException.class, () -> emails.get("x", RequestOptions.builder().maxRetries(0).build()));
    assertEquals(cls, e.getClass().getSimpleName());
    assertEquals(status, e.getStatusCode());
    assertEquals(code, e.getCode());
    assertEquals(code + " happened", e.getMessage());
    assertEquals("invalid_request_error", e.getType());
    assertEquals("req_1", e.getRequestId());
    assertEquals("req_1", e.getHeaders().firstValue("x-request-id").orElse(null));
    assertEquals(code, ((Map<?, ?>) ((Map<?, ?>) e.getBody()).get("error")).get("code"));
  }

  @Test
  void readsTheFlatErrorShapeAndABodyThatIsNotJson() throws IOException {
    EmailsService flat = new EmailsService(client(json(422, "{\"statusCode\":422,\"name\":\"missing_required_field\",\"message\":\"subject is required\"}")));
    ZapSMTPException e = assertThrows(ValidationException.class, () -> flat.get("x"));
    assertEquals("missing_required_field", e.getCode());
    assertEquals("subject is required", e.getMessage());
    assertEquals("", e.getType());
    EmailsService text = new EmailsService(client(TestServer.text(418, "bad gateway")));
    e = assertThrows(ZapSMTPException.class, () -> text.get("x"));
    assertEquals(418, e.getStatusCode());
    assertEquals("", e.getCode());
    assertEquals("HTTP 418", e.getMessage());
    assertNull(e.getRequestId());
    assertEquals("bad gateway", e.getBody());
    EmailsService empty = new EmailsService(client(TestServer.text(404, "")));
    e = assertThrows(NotFoundException.class, () -> empty.get("x"));
    assertNull(e.getBody());
    EmailsService odd = new EmailsService(client(json(404, "{\"error\":\"nope\",\"name\":null,\"message\":7}")));
    e = assertThrows(NotFoundException.class, () -> odd.get("x"));
    assertEquals("", e.getCode());
    assertEquals("7", e.getMessage());
  }

  @Test
  void givesRetryAfterOnRateLimits() throws IOException {
    EmailsService emails = new EmailsService(client(apiError(429, "monthly_quota_exceeded").header("Retry-After", "7")));
    RateLimitException e = assertThrows(RateLimitException.class, () -> emails.get("x"));
    assertEquals(Duration.ofSeconds(7), e.getRetryAfter());
    assertEquals(1, server.count());
    assertNull(new RateLimitException("x", 429, "", "", null, null, null).getRetryAfter());
  }

  @Test
  void wrapsNetworkFailures() throws IOException {
    server = new TestServer();
    String url = server.url();
    server.close();
    ApiClient c = client(ZapSMTP.builder().apiKey("k").baseUrl(url).maxRetries(0));
    ConnectionException e = assertThrows(ConnectionException.class, () -> new EmailsService(c).get("x"));
    assertEquals(0, e.getStatusCode());
    assertInstanceOf(IOException.class, e.getCause());
    server = null;
  }

  @Test
  void namesAFailureWithoutAMessage() {
    ApiClient c = client(ZapSMTP.builder().apiKey("k").baseUrl("http://127.0.0.1:9/v1").maxRetries(0)
        .httpClient(new FailingHttpClient(new IOException())));
    ConnectionException e = assertThrows(ConnectionException.class, () -> new EmailsService(c).get("x"));
    assertEquals("java.io.IOException", e.getMessage());
    ApiClient odd = client(ZapSMTP.builder().apiKey("k").baseUrl("http://127.0.0.1:9/v1").maxRetries(0)
        .httpClient(new FailingHttpClient(new IllegalStateException("boom"))));
    e = assertThrows(ConnectionException.class, () -> new EmailsService(odd).get("x"));
    assertEquals("java.lang.IllegalStateException: boom", e.getMessage());
    assertInstanceOf(IllegalStateException.class, e.getCause().getCause());
  }

  @Test
  void timesOut() throws IOException {
    EmailsService emails = new EmailsService(client(json(200, "{}").delay(2000)));
    ConnectionException e = assertThrows(ConnectionException.class,
        () -> emails.get("x", RequestOptions.builder().maxRetries(0).timeout(Duration.ofMillis(100)).build()));
    assertEquals("timed out after 100 ms", e.getMessage());
  }

  @Test
  void stopsWhenTheThreadIsInterruptedWithoutRetrying() throws Exception {
    EmailsService emails = new EmailsService(client(json(200, "{}").delay(3000)));
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicReference<Boolean> flag = new AtomicReference<>();
    java.lang.Thread t = new java.lang.Thread(() -> {
      try {
        emails.get("x");
      } catch (RuntimeException e) {
        thrown.set(e);
        flag.set(java.lang.Thread.currentThread().isInterrupted());
      }
    });
    t.start();
    while (server.count() == 0) {
      java.lang.Thread.sleep(10);
    }
    t.interrupt();
    t.join();
    assertEquals("Request interrupted", thrown.get().getMessage());
    assertTrue(flag.get());
    assertEquals(1, server.count());
  }

  @Test
  void anInterruptedThreadSendsNothing() throws IOException {
    EmailsService emails = new EmailsService(client(json(200, "{}")));
    java.lang.Thread.currentThread().interrupt();
    try {
      ConnectionException e = assertThrows(ConnectionException.class, () -> emails.get("email-1"));
      assertEquals("Request not sent: the thread is interrupted", e.getMessage());
      assertEquals(0, e.getStatusCode());
    } finally {
      assertTrue(java.lang.Thread.interrupted());
    }
    assertEquals(0, server.count());
  }

  @Test
  void anInterruptedWaitStopsRetrying() throws IOException {
    server = new TestServer(apiError(503, "service_unavailable"));
    ApiClient c = new ApiClient(ZapSMTP.builder().apiKey("k").baseUrl(server.url()).build().options(), clock, (ms) -> {
      throw new InterruptedException();
    });
    try {
      ConnectionException e = assertThrows(ConnectionException.class, () -> new EmailsService(c).get("x"));
      assertEquals("Interrupted while waiting to retry", e.getMessage());
    } finally {
      assertTrue(java.lang.Thread.interrupted());
    }
  }

  @Test
  void theTimeoutCoversReadingTheBody() throws IOException {
    EmailsService emails = new EmailsService(client(TestServer.stall()));
    ConnectionException e = assertThrows(ConnectionException.class,
        () -> emails.get("email-1", RequestOptions.builder().maxRetries(0).timeout(Duration.ofMillis(300)).build()));
    assertEquals("timed out after 300 ms", e.getMessage());
  }

  @Test
  void aBrokenBodyIsAConnectionErrorAndASafeRequestIsRetried() throws IOException {
    EmailsService once = new EmailsService(client(TestServer.broken()));
    ConnectionException e = assertThrows(ConnectionException.class,
        () -> once.get("email-1", RequestOptions.builder().maxRetries(0).build()));
    assertInstanceOf(IOException.class, e.getCause());
    // the status and headers arrived: they are kept
    assertEquals(200, e.getStatusCode());
    assertEquals("req_broken", e.getRequestId());
    server.close();
    EmailsService retried = new EmailsService(client(TestServer.broken(), json(200, "{\"id\":\"e1\"}")));
    assertEquals("e1", retried.get("email-1").getId());
    assertEquals(2, server.count());
    assertEquals(1, sleeps.size());
  }

  // ---- retries

  @Test
  void retriesSafeRequestsOn5xxAndNetworkErrorsHonouringRetryAfter() throws IOException {
    EmailsService emails = new EmailsService(client(apiError(503, "service_unavailable").header("Retry-After", "2"),
        TestServer.broken(), json(200, "{\"id\":\"e1\"}")));
    assertEquals("e1", emails.get("e1").getId());
    assertEquals(3, server.count());
    assertEquals(2000L, sleeps.get(0));
    assertTrue(sleeps.get(1) >= 500 && sleeps.get(1) <= 1000, "jittered: " + sleeps);
  }

  @Test
  void retriesASendWithTheSameIdempotencyKey() throws IOException {
    EmailsService emails = new EmailsService(client(apiError(502, "bad_gateway"), json(200, "{\"id\":\"e1\"}")));
    emails.send(SendEmailParams.builder().to("b").build());
    List<String> keys = server.seen().stream().map((s) -> s.header("Idempotency-Key")).collect(Collectors.toList());
    assertEquals(2, keys.size());
    assertEquals(keys.get(0), keys.get(1));
  }

  @Test
  void doesNotRetryACreateOn5xxOrALostConnectionButDoesOn429() throws IOException {
    DomainsCreateParams p = DomainsCreateParams.builder().name("a.test").build();
    DomainsService five = new DomainsService(client(apiError(500, "internal_error")));
    assertThrows(ServerException.class, () -> five.create(p));
    assertEquals(1, server.count());
    server.close();
    DomainsService lost = new DomainsService(client(TestServer.broken()));
    assertThrows(ConnectionException.class, () -> lost.create(p));
    assertEquals(1, server.count());
    server.close();
    DomainsService limited = new DomainsService(client(apiError(429, "rate_limit_exceeded").header("Retry-After", "1"),
        json(201, "{\"id\":\"d1\"}")));
    Domain d = limited.create(p);
    assertEquals("d1", d.getId());
    assertEquals(List.of(1000L), sleeps);
  }

  @Test
  void givesUpAfterMaxRetries() throws IOException {
    server = new TestServer(apiError(503, "service_unavailable").header("Retry-After", "0"));
    ApiClient c = client(ZapSMTP.builder().apiKey("k").baseUrl(server.url()).maxRetries(3));
    assertThrows(ServerException.class, () -> new EmailsService(c).get("x"));
    assertEquals(4, server.count());
    assertThrows(ServerException.class, () -> new EmailsService(c).get("x", RequestOptions.builder().maxRetries(0).build()));
    assertEquals(5, server.count());
  }

  @Test
  void retriesAnIdempotentRequestStillInProgress() throws IOException {
    EmailsService emails = new EmailsService(client(apiError(409, "idempotency_request_in_progress"), json(200, "{\"id\":\"e1\"}")));
    assertEquals("e1", emails.send(SendEmailParams.builder().to("b").build()).getId());
    EmailsService reused = new EmailsService(client(apiError(409, "idempotency_key_reused")));
    assertThrows(ConflictException.class, () -> reused.send(SendEmailParams.builder().to("b").build()));
    assertEquals(1, server.count());
  }

  @Test
  void decidesWhatIsWorthRetrying() {
    assertTrue(Retry.retryable(429, "overloaded", false));
    assertFalse(Retry.retryable(429, "daily_quota_exceeded", true));
    assertTrue(Retry.retryable(409, "idempotency_request_in_progress", true));
    assertFalse(Retry.retryable(409, "idempotency_key_reused", true));
    assertTrue(Retry.retryable(503, "service_unavailable", true));
    assertFalse(Retry.retryable(503, "service_unavailable", false));
    assertFalse(Retry.retryable(422, "invalid_parameter", true));
  }

  @Test
  void backsOffExponentiallyWithJitterCappedOrAsRetryAfterSays() {
    for (int a = 0; a < 40; a++) {
      long cap = Math.min(500L << Math.min(a, 5), 8000);
      long d = Retry.backoff(a, null).toMillis();
      assertTrue(d >= cap / 2 && d <= cap, a + ": " + d);
    }
    assertEquals(Duration.ofSeconds(3), Retry.backoff(0, Duration.ofSeconds(3)));
    assertEquals(Duration.ofSeconds(60), Retry.backoff(0, Duration.ofSeconds(600)));
  }

  @Test
  void readsRetryAfterAsSecondsOrAnHttpDate() throws IOException {
    clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    EmailsService emails = new EmailsService(client(apiError(503, "s").header("Retry-After", "Thu, 01 Jan 2026 00:00:03 GMT"), json(200, "{}")));
    emails.get("x");
    assertEquals(List.of(3000L), sleeps);
    assertEquals(Duration.ofMillis(1500), retryAfter(" 1.5 "));
    assertEquals(Duration.ZERO, retryAfter("-3"));
    assertEquals(Duration.ofSeconds(100), retryAfter("1e2"));
    assertNull(retryAfter("soon"));
    assertNull(retryAfter("Infinity"));
    assertNull(retryAfter(null));
    assertEquals(Duration.ZERO, retryAfter("Thu, 01 Jan 2025 00:00:00 GMT"));
  }

  private Duration retryAfter(String v) {
    Map<String, List<String>> m = v == null ? Map.of() : Map.of("Retry-After", List.of(v));
    return Retry.retryAfter(HttpHeaders.of(m, (a, b) -> true), clock);
  }

  // ---- paging

  private static TestServer.Answer page(boolean more, String extra, String... ids) {
    String data = java.util.Arrays.stream(ids).map((id) -> "{\"id\":\"" + id + "\"}").collect(Collectors.joining(","));
    return json(200, "{\"object\":\"list\",\"has_more\":" + more + ",\"data\":[" + data + "]" + extra + "}");
  }

  @Test
  void walksEveryPageWithBeforeTheLastIdKeepingTheFilters() throws IOException {
    EmailsService emails = new EmailsService(client(page(true, "", "a", "b"), page(false, "", "c")));
    List<String> ids = new ArrayList<>();
    for (Email e : emails.list(EmailsListParams.builder().limit(2).after("2026-01-01T00:00:00Z").build()).autoPagingIterable()) {
      ids.add(e.getId());
    }
    assertEquals(List.of("a", "b", "c"), ids);
    assertEquals("/v1/emails?limit=2&after=2026-01-01T00%3A00%3A00Z&before=b", server.seen().get(1).target());
  }

  @Test
  void walksSuppressionsWithNextCursor() throws IOException {
    SuppressionsService s = new SuppressionsService(client(page(true, ",\"next_cursor\":\"cur1\"", "a"),
        page(true, ",\"next_cursor\":null", "b")));
    List<Object> ids = s.list().stream().map((x) -> x.getAdditionalProperties().get("id")).collect(Collectors.toList());
    assertEquals(List.of("a", "b"), ids);
    assertEquals("/v1/suppressions?after=cur1", server.seen().get(1).target());
    assertEquals(2, server.count());
    Page<Suppression> first = s.list();
    assertEquals(null, first.getNextCursor());
  }

  @Test
  void givesOnePageWithoutAskingForTheNext() throws IOException {
    Page<Email> first = new EmailsService(client(page(true, "", "a"))).list();
    assertEquals("a", first.getData().get(0).getId());
    assertTrue(first.hasMore());
    assertTrue(first.hasNextPage());
    assertTrue(first.hasNextPage());
    assertEquals(1, server.count());
  }

  @Test
  void stopsWhereNoNextPageCanBeAskedFor() throws IOException {
    Page<Email> noId = new EmailsService(client(json(200, "{\"object\":\"list\",\"has_more\":true,\"data\":[{}]}"))).list();
    assertFalse(noId.hasNextPage());
    assertNull(noId.getNextPage());
    Page<Domain> unpaged = new DomainsService(client(page(true, "", "a"))).list();
    assertNull(unpaged.getNextPage());
    assertNull(new EmailsService(client(page(false, "", "a"))).list().getNextPage());
    Page<Email> empty = new EmailsService(client(json(200, "{\"object\":\"list\",\"data\":{}}"))).list();
    assertEquals(List.of(), empty.getData());
    assertFalse(empty.hasMore());
    Page<com.zapsmtp.models.Attachment> bad = new com.zapsmtp.services.EmailsAttachmentsService(
        client(json(200, "{\"data\":[{\"id\":\"a\",\"size\":99999999999999999999999}],\"has_more\":true}"))).list("e1");
    assertEquals("a", bad.getData().get(0).getId()); // kept: only its too large size is null
    assertNull(bad.getData().get(0).getSize());
    assertFalse(new EmailsService(client(json(200, "{\"data\":[{\"id\":5}],\"has_more\":true}"))).list().hasNextPage());
    assertFalse(new EmailsService(client(json(200, "{\"data\":[{\"id\":\"\"}],\"has_more\":true}"))).list().hasNextPage());
    Page<Email> none = new EmailsService(client(TestServer.text(200, ""))).list();
    assertEquals(List.of(), none.getData());
    var it = new EmailsService(client(page(false, "", "a"))).list().autoPagingIterable().iterator();
    assertEquals("a", it.next().getId());
    assertFalse(it.hasNext());
    assertThrows(NoSuchElementException.class, it::next);
  }

  @Test
  void pagesConversationsByLastActivityAndId() throws IOException {
    ThreadsService threads = new ThreadsService(client(json(200,
        "{\"object\":\"list\",\"has_more\":true,\"data\":[{\"id\":\"019aaa00\",\"last_message_at\":\"2026-10-28T08:00:00Z\"}]}")));
    threads.list(ThreadsListParams.builder().limit(1).unread(true).build()).getNextPage();
    assertEquals("/v1/threads?limit=1&unread=true&before=2026-10-28T08%3A00%3A00Z&before_id=019aaa00", server.last().target());
    assertFalse(new ThreadsService(client(page(true, "", "x"))).list().hasNextPage());
    assertFalse(new ThreadsService(client(json(200, "{\"has_more\":true,\"data\":[{\"last_message_at\":\"t\"}]}"))).list().hasNextPage());
  }

  @Test
  void pagesReceivedAttachmentsForwardAfterTheLastId() throws IOException {
    EmailsReceivingAttachmentsService a = new EmailsReceivingAttachmentsService(client(page(true, "", "a1", "a2")));
    a.list("m1", EmailsReceivingAttachmentsListParams.builder().limit(2).build()).getNextPage();
    assertEquals("/v1/emails/receiving/m1/attachments?limit=2&after=a2", server.last().target());
    assertFalse(new EmailsReceivingAttachmentsService(client(page(true, ""))).list("m1").hasNextPage());
  }

  @Test
  void pagesLogsBackOrForwardAfterTheNewest() throws IOException {
    new LogsService(client(page(true, "", "l3", "l2"))).list().getNextPage();
    assertEquals("/v1/logs?before=l2", server.last().target());
    new LogsService(client(page(true, "", "l9", "l8"))).list(LogsListParams.builder().after("l7").build()).getNextPage();
    assertEquals("/v1/logs?after=l9", server.last().target());
    assertFalse(new LogsService(client(page(true, ""))).list(LogsListParams.builder().after("l7").build()).hasNextPage());
    assertFalse(new LogsService(client(page(true, ""))).list().hasNextPage());
    assertFalse(new EmailsReceivingService(client(page(true, ""))).list().hasNextPage());
    Page<Log> logs = new LogsService(client(page(true, "", "l1"))).list();
    assertTrue(logs.hasNextPage());
  }

  @Test
  void pageOfMakesStubs() {
    Page<String> second = Page.of(java.util.Arrays.asList("c", null), false, null, null);
    Page<String> first = Page.of(List.of("a", "b"), true, "cur", () -> second);
    assertEquals(java.util.Arrays.asList("a", "b", "c", null), first.stream().collect(Collectors.toList()));
    assertEquals("cur", first.getNextCursor());
    assertThrows(UnsupportedOperationException.class, () -> first.getData().add("x"));
  }

  @Test
  void optionsAreChecked() {
    assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().timeout(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().timeout(Duration.ofMillis(-1)));
    assertThrows(IllegalArgumentException.class, () -> RequestOptions.builder().maxRetries(-1));
    assertThrows(NullPointerException.class, () -> RequestOptions.builder().header("a", null));
    assertThrows(NullPointerException.class, () -> RequestOptions.builder().header(null, "a"));
    RequestOptions none = RequestOptions.none();
    assertNull(none.getIdempotencyKey());
    assertNull(none.getTimeout());
    assertNull(none.getMaxRetries());
    assertEquals(Map.of(), none.getHeaders());
    assertNotEquals(none, RequestOptions.builder().build());
    ClientOptions o = ZapSMTP.builder().apiKey("k").build().options();
    assertEquals(ClientOptions.DEFAULT_TIMEOUT, o.getTimeout());
  }

  /** An HttpClient whose every request fails with the given exception. */
  static final class FailingHttpClient extends HttpClient {
    private final Throwable failure;

    FailingHttpClient(Throwable failure) {
      this.failure = failure;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      CompletableFuture<HttpResponse<T>> f = new CompletableFuture<>();
      f.completeExceptionally(failure);
      return f;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      return sendAsync(request, handler);
    }
  }
}
