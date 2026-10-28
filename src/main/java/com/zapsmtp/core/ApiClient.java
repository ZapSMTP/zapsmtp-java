package com.zapsmtp.core;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zapsmtp.ClientOptions;
import com.zapsmtp.Page;
import com.zapsmtp.RequestOptions;
import com.zapsmtp.Version;
import com.zapsmtp.ZapResponse;
import com.zapsmtp.errors.ConnectionException;
import com.zapsmtp.errors.ZapSMTPException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends API requests: headers, retries, errors, decoding and paging (docs/DESIGN-sdks.md).
 *
 * <p>Internal: not part of the SDK's public API. Use {@link com.zapsmtp.ZapSMTP}.
 */
public final class ApiClient {
  private static final String USER_AGENT =
      "zapsmtp-java/" + Version.VERSION + " (java/" + System.getProperty("java.version") + ")";

  private final ClientOptions options;
  private final HttpClient http;
  private final Clock clock;
  private final Sleeper sleeper;

  /**
   * Creates a client.
   *
   * @param options the client's configuration
   */
  public ApiClient(ClientOptions options) {
    this(options, Clock.systemUTC(), java.lang.Thread::sleep);
  }

  ApiClient(ClientOptions options, Clock clock, Sleeper sleeper) {
    this.options = options;
    this.http = options.getHttpClient() != null ? options.getHttpClient()
        : HttpClient.newBuilder().connectTimeout(options.getTimeout()).build();
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /**
   * The client's configuration.
   *
   * @return the options
   */
  public ClientOptions getOptions() {
    return options;
  }

  /**
   * Sends a request and decodes its answer.
   *
   * @param request the request
   * @param type the model of the answer; {@code Void.class} for none
   * @param requestOptions this call's options
   * @param <T> the model
   * @return the answer
   * @throws ZapSMTPException for an error answer or no answer
   */
  public <T> ZapResponse<T> request(ApiRequest request, Class<T> type, RequestOptions requestOptions) {
    Answer a = send(request, requestOptions);
    T data = type == Void.class ? null : Json.decode(a.json, Json.MAPPER.constructType(type));
    return new ZapResponse<>(data, a.response.statusCode(), a.response.headers(), a.text);
  }

  /**
   * Sends a list request and decodes one page of it.
   *
   * @param request the request
   * @param type the model of an item
   * @param paging how the next page is asked for
   * @param requestOptions this call's options, also used for the next pages
   * @param <T> the model
   * @return the page
   * @throws ZapSMTPException for an error answer or no answer
   */
  public <T> ZapResponse<Page<T>> requestPage(ApiRequest request, Class<T> type, Paging paging,
      RequestOptions requestOptions) {
    Answer a = send(request, requestOptions);
    JsonNode body = a.json == null ? JsonNodeFactory.instance.objectNode() : a.json;
    JsonNode data = body.path("data").isArray() ? body.get("data") : JsonNodeFactory.instance.arrayNode();
    // item by item: one that cannot be decoded is null, its neighbours are kept
    JavaType itemType = Json.MAPPER.constructType(type);
    List<T> items = new ArrayList<>();
    data.forEach((item) -> items.add(Json.decode(item, itemType)));
    boolean hasMore = body.path("has_more").asBoolean(false);
    String cursor = Paging.text(body, "next_cursor");
    Map<String, Object> next = hasMore ? paging.next(request.query(), data, cursor) : null;
    ApiRequest nextRequest = next == null ? null : request.withQuery(next);
    Page<T> page = Page.of(items, hasMore, cursor,
        nextRequest == null ? null : () -> requestPage(nextRequest, type, paging, requestOptions).getData());
    return new ZapResponse<>(page, a.response.statusCode(), a.response.headers(), a.text);
  }

  private static final class Answer {
    final HttpResponse<byte[]> response;
    final String text;
    final JsonNode json;

    Answer(HttpResponse<byte[]> response) {
      this.response = response;
      this.text = new String(response.body(), StandardCharsets.UTF_8);
      this.json = Json.parse(text);
    }
  }

  private Answer send(ApiRequest req, RequestOptions ro) {
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.put("Authorization", "Bearer " + options.getApiKey());
    headers.put("Accept", "application/json");
    headers.put("User-Agent", USER_AGENT);
    headers.putAll(options.getDefaultHeaders());
    req.headers().forEach((k, v) -> {
      if (v != null) {
        headers.put(k, v);
      }
    });
    if (req.idempotent()) {
      headers.put("Idempotency-Key", ro.getIdempotencyKey() != null ? ro.getIdempotencyKey() : UUID.randomUUID().toString());
    }
    headers.putAll(ro.getHeaders()); // a caller's header replaces the SDK's, whatever its case
    String key = headers.get("Idempotency-Key");
    if (key != null && key.isBlank()) {
      // the API does not deduplicate an empty key: a retried send could go out twice
      throw ApiRequest.invalid("idempotencyKey must not be empty");
    }
    HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
    if (req.multipart() != null) {
      headers.put("Content-Type", req.multipart().contentType());
      body = HttpRequest.BodyPublishers.ofByteArray(req.multipart().bytes());
    } else if (req.body() != null) {
      headers.put("Content-Type", "application/json");
      body = HttpRequest.BodyPublishers.ofByteArray(Json.write(req.body()));
    }
    HttpRequest.Builder hb = HttpRequest.newBuilder(url(req)).method(req.method(), body);
    headers.forEach(hb::header);
    HttpRequest httpRequest = hb.build();
    int maxRetries = ro.getMaxRetries() != null ? ro.getMaxRetries() : options.getMaxRetries();
    Duration timeout = ro.getTimeout() != null ? ro.getTimeout() : options.getTimeout();
    String method = req.method();
    boolean safe = method.equals("GET") || method.equals("PUT") || method.equals("DELETE") || req.idempotent();

    for (int attempt = 0; ; attempt++) {
      HttpResponse<byte[]> res;
      AtomicReference<HttpResponse.ResponseInfo> head = new AtomicReference<>();
      try {
        res = attempt(httpRequest, timeout, head);
      } catch (IOException e) {
        // a timeout or a broken connection (sending, or reading the answer): the request may have reached the
        // server, so only a request that is safe to repeat is sent again
        if (attempt < maxRetries && safe) {
          pause(Retry.backoff(attempt, null));
          continue;
        }
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        HttpResponse.ResponseInfo info = head.get();
        // the status and headers survive a body that could not be read
        throw info == null ? new ConnectionException(message, e)
            : new ConnectionException(message, e, info.statusCode(), info.headers());
      }
      Answer answer = new Answer(res);
      if (res.statusCode() / 100 == 2) {
        return answer;
      }
      ZapSMTPException err = ApiErrors.of(res.statusCode(), answer.json, res.headers());
      if (attempt < maxRetries && Retry.retryable(res.statusCode(), err.getCode(), safe)) {
        pause(Retry.backoff(attempt, Retry.retryAfter(res.headers(), clock)));
        continue;
      }
      throw err;
    }
  }

  // One attempt, the body read included: the timeout covers a server that sends its headers and then stalls.
  private HttpResponse<byte[]> attempt(HttpRequest request, Duration timeout,
      AtomicReference<HttpResponse.ResponseInfo> head) throws IOException {
    if (java.lang.Thread.currentThread().isInterrupted()) {
      throw new ConnectionException("Request not sent: the thread is interrupted", null);
    }
    CompletableFuture<HttpResponse<byte[]>> f = http.sendAsync(request, (info) -> {
      head.set(info);
      return HttpResponse.BodySubscribers.ofByteArray();
    });
    try {
      return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      f.cancel(true);
      throw new HttpTimeoutException("timed out after " + timeout.toMillis() + " ms");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw cause instanceof IOException ? (IOException) cause : new IOException(cause.toString(), cause);
    } catch (InterruptedException e) {
      f.cancel(true);
      java.lang.Thread.currentThread().interrupt();
      throw new ConnectionException("Request interrupted", e);
    }
  }

  private void pause(Duration d) {
    try {
      sleeper.sleep(d.toMillis());
    } catch (InterruptedException e) {
      java.lang.Thread.currentThread().interrupt();
      throw new ConnectionException("Interrupted while waiting to retry", e);
    }
  }

  private URI url(ApiRequest req) {
    StringBuilder q = new StringBuilder();
    for (Map.Entry<String, Object> e : req.query().entrySet()) {
      Object v = e.getValue();
      if (v instanceof Collection) {
        for (Object x : (Collection<?>) v) {
          add(q, e.getKey(), x);
        }
      } else if (v instanceof Map) {
        for (Map.Entry<?, ?> m : ((Map<?, ?>) v).entrySet()) {
          add(q, e.getKey() + "[" + m.getKey() + "]", m.getValue());
        }
      } else if (v != null) {
        add(q, e.getKey(), v);
      }
    }
    return URI.create(options.getBaseUrl() + req.path() + (q.length() == 0 ? "" : "?" + q));
  }

  private static void add(StringBuilder q, String key, Object value) {
    if (q.length() > 0) {
      q.append('&');
    }
    q.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
        .append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
  }
}
