# ZapSMTP Java SDK

The official Java library for the [ZapSMTP](https://zapsmtp.com) email API: send, receive and track email,
manage templates, domains, webhooks and everything else the API does.

- Every API operation, with typed, immutable parameters and responses
- Safe automatic retries: sends carry an `Idempotency-Key`, so a retry never sends twice
- Iterate or stream every item of every page of any list
- Webhook signature verification, single events and batches
- Java 11+, `java.net.http`, one dependency (Jackson)

## Install

Maven:

```xml
<dependency>
  <groupId>com.zapsmtp</groupId>
  <artifactId>zapsmtp-java</artifactId>
  <version>0.1.5</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.zapsmtp:zapsmtp-java:0.1.6")
```

## Send an email

```java
import com.zapsmtp.ZapSMTP;
import com.zapsmtp.models.SendEmailResponse;
import com.zapsmtp.params.SendEmailParams;
import com.zapsmtp.params.Tag;

ZapSMTP zap = ZapSMTP.builder().apiKey("zap_...").build(); // or ZapSMTP.fromEnv(): reads ZAP_API_KEY

SendEmailResponse sent = zap.emails().send(SendEmailParams.builder()
    .from("Acme <hello@acme.com>")
    .to("jane@example.org")
    .subject("Your receipt")
    .html("<p>Thanks for your order.</p>")
    .tags(Tag.builder().name("category").value("receipt").build())
    .metadata(Map.of("order_id", "A-1042"))
    .build());
System.out.println(sent.getId());
```

Build one client and share it: it is thread-safe. Parameters are immutable: `build()` takes a deep copy of the
lists and maps you pass, so changing them afterwards changes nothing. `toBuilder()` gives a builder holding an
object's values.

### Templates

Send a published template with variables:

```java
zap.emails().send(SendEmailParams.builder()
    .from("Acme <billing@acme.com>")
    .to("jane@example.org")
    .template(TemplateRef.builder().id("receipt").variables(Map.of("name", "Jane", "order_id", "A-1042")).build())
    .build());
```

Manage them with `zap.templates()`: `create`, `update`, `publish`, `render`, `versions().list(id)`,
`versions().restore(id, versionId)` and more.

### Attachments

```java
import com.zapsmtp.Attachments;

zap.emails().send(SendEmailParams.builder()
    .from("Acme <billing@acme.com>")
    .to("jane@example.org")
    .subject("Your invoice")
    .text("Attached.")
    .attachments(
        Attachments.fromFile(Path.of("invoice.pdf")),                       // content type from the extension
        Attachments.fromBytes("logo.png", logo).toBuilder().contentId("logo").build()) // inline: cid:logo
    .build());
```

### Batch

Up to 100 emails in one request. In permissive mode the valid ones are sent and the invalid ones reported:

```java
SendBatchResponse r = zap.emails().sendBatch(List.of(first, second),
    SendBatchOptions.builder().validation(SendBatchOptions.Validation.PERMISSIVE).build());
r.getErrors().forEach((e) -> System.out.println(e.getIndex() + ": " + e.getMessage()));
```

## Lists

A list method returns one page. `autoPagingIterable()` and `stream()` walk every item of every page,
asking for the next page when they reach it:

```java
for (Email email : zap.emails().list(EmailsListParams.builder().status(EmailsListParams.Status.BOUNCED).build())
    .autoPagingIterable()) {
  System.out.println(email.getId());
}

List<String> addresses = zap.suppressions().list().stream().map(Suppression::getEmail).collect(Collectors.toList());

Page<Email> page = zap.emails().list(EmailsListParams.builder().limit(50).build());
page.getData();       // this page's items
page.hasNextPage();   // no request is made
page.getNextPage();   // the next page, or null after the last one
```

Parameters with fixed values have their constants in the params class, next to the field:
`EmailsListParams.Status.BOUNCED`, `ApiKeysCreateParams.Permission.SENDING_ACCESS`,
`WebhooksCreateParams.Event.EMAIL_DELIVERED`, `StatsMetricsParams.Granularity.DAY` and so on.

A conversation is `com.zapsmtp.models.Thread`, which has the simple name of `java.lang.Thread`:
import it by name (then write `java.lang.Thread` for the other one) or write it out in full.

## Responses

Response models are deeply immutable: lists and maps, also nested ones and the free-form JSON fields, cannot be
changed. A getter returns null when the API left the field out; fields this SDK version does not know yet are kept
in `getAdditionalProperties()`. A value of the wrong type is null instead of an error, among them a fractional or
too large number for an integer field and a non-finite number. Times are `OffsetDateTime`. Values with a fixed set of
allowed strings have constants, such as `EmailsListParams.Status.DELIVERED`, and stay plain strings, so a new
value never breaks decoding.

## Errors

Every error is an unchecked `ZapSMTPException` (package `com.zapsmtp.errors`) with `getStatusCode()`,
`getCode()`, `getType()`, `getRequestId()`, `getHeaders()` and `getBody()`:

| Class | When |
|---|---|
| `AuthenticationException` | 401: missing, malformed or revoked key |
| `PermissionException` | 403: the key may not do this |
| `NotFoundException` | 404 |
| `ConflictException` | 409 |
| `ValidationException` | 400, 413, 422; also an empty, `.` or `..` id, or an empty Idempotency-Key, refused before sending |
| `RateLimitException` | 429, with `getRetryAfter()` |
| `ServerException` | 5xx |
| `ConnectionException` | no answer: network failure, timeout, interrupted thread |

```java
try {
  zap.emails().get(id);
} catch (NotFoundException e) {
  // ...
} catch (RateLimitException e) {
  Duration wait = e.getRetryAfter(); // null when the API gave none
} catch (ZapSMTPException e) {
  log.warn("{} {} (request {})", e.getStatusCode(), e.getCode(), e.getRequestId());
}
```

## Retries and timeouts

A failed request is retried twice by default, with exponential backoff (0.5 s, 1 s, 2 s, … up to 8 s, with
jitter), or after `Retry-After` when the API sends it (up to 60 s). Retried: network errors and timeouts, 408,
429 (except monthly and daily quota errors and `thread_reply_limit`), 409 `idempotency_request_in_progress`, and 500, 502, 503, 504.

Only requests that are safe to repeat are retried after a 5xx or a lost connection: GET, PUT, DELETE, and the
sends (`emails().send`, `emails().sendBatch`, `emails().receiving().reply`), which carry an `Idempotency-Key`:
a random UUID, the same on every retry, unless you set one. Other POST and PATCH requests are retried on 429 only.

The timeout covers each attempt, the response body included: 60 s by default.

```java
ZapSMTP zap = ZapSMTP.builder().apiKey("zap_...").maxRetries(4).timeout(Duration.ofSeconds(20)).build();

zap.emails().send(params, RequestOptions.builder()
    .idempotencyKey("order-1042")       // a retry of your own job never sends twice
    .timeout(Duration.ofSeconds(5))
    .maxRetries(0)
    .header("X-Trace-Id", traceId)
    .build());
```

## Response headers

Every API method has a `...WithResponse` twin (the `uploadFile` helpers excepted) that gives the status and
headers with the data, such as the request id and the rate-limit and quota headers:

```java
ZapResponse<SendEmailResponse> r = zap.emails().sendWithResponse(params);
r.getData().getId();
r.getRequestId();
r.getHeader("RateLimit-Remaining");
```

## Webhooks

Check every webhook request's `Zap-Signature` with the endpoint's signing secret before trusting it. Pass the
body exactly as received. A webhook with batch delivery sends a list of events: use `verifyEvents`, which
returns every event of a batch, or the one event of any other request.

```java
@RestController
class ZapSMTPWebhook {
  private final String secret = System.getenv("ZAP_WEBHOOK_SECRET"); // whsec_...

  @PostMapping("/webhooks/zapsmtp")
  ResponseEntity<Void> receive(@RequestBody String body, @RequestHeader("Zap-Signature") String signature) {
    List<WebhookEvent> events;
    try {
      events = Webhooks.verifyEvents(body, signature, secret);
    } catch (WebhookVerificationException e) {
      return ResponseEntity.badRequest().build();
    }
    for (WebhookEvent event : events) {
      if (event.getType().equals("email.bounced")) {
        String emailId = (String) event.getData().get("email_id");
        // ...
      }
    }
    return ResponseEntity.ok().build();
  }
}
```

`Webhooks.verify(body, headers, secret)` takes a map of headers instead (names in any case), and
`Webhooks.verify(body, signature, secret, tolerance, clock)` sets how old a signature may be (5 minutes by
default) and the clock to check it against. `zap.webhooks().verify(...)` and `verifyEvents(...)` do the same.
`event.getData(MyType.class)` decodes the event's data into a class.

## Configuration

| Builder method | Default |
|---|---|
| `apiKey(String)` | the `ZAP_API_KEY` environment variable |
| `baseUrl(String)` | `ZAP_BASE_URL`, else `https://api.zapsmtp.com/v1` |
| `timeout(Duration)` | 60 s per attempt |
| `maxRetries(int)` | 2 |
| `defaultHeader(String, String)` | none |
| `httpClient(HttpClient)` | a `java.net.http.HttpClient` of its own; pass one for proxies, TLS or an executor |

The JAR's automatic module name is `com.zapsmtp`.

## Reference

The [API reference](https://zapsmtp.com/docs/) documents every operation. The SDK names them after it:
`zap.emails().send`, `zap.emails().receiving().list`, `zap.templates().versions().get`, and so on; their Javadoc
carries the reference's description.

## Development

The services (`com.zapsmtp.services`), parameters (`com.zapsmtp.params`) and models (`com.zapsmtp.models`) are
generated from the API reference by `node sdks/codegen/generate.mjs`; never edit them by hand. The rest is
written by hand.

```bash
# Java and Maven in Docker, next to the conformance server (sdks/scripts/docker-test.sh)
sdks/scripts/docker-test.sh maven:3.9-eclipse-temurin-11 java 'mvn -B verify'
sdks/scripts/docker-test.sh maven:3.9-eclipse-temurin-21 java 'mvn -B verify'
```

`mvn verify` compiles with `-Xlint:all -Werror`, runs the tests (every operation against the conformance
server, which is `ZAP_CONFORMANCE_URL` or started with `node`; the core; every webhook vector), requires 100%
line and branch coverage, and builds the Javadoc with every warning an error. `sdks/live/run.sh java` runs the
live test against the dev API. `mvn -P release deploy` signs and publishes to Maven Central.
