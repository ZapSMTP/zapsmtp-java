package com.zapsmtp;

import java.net.http.HttpHeaders;

/**
 * An API answer: the decoded data with the status and headers ({@code ...WithResponse} methods).
 *
 * @param <T> the data's type
 */
public final class ZapResponse<T> {
  private final T data;
  private final int statusCode;
  private final HttpHeaders headers;
  private final String rawBody;

  /**
   * Creates an answer, for example to stub the SDK in tests.
   *
   * @param data the decoded data, or null
   * @param statusCode the HTTP status
   * @param headers the headers
   * @param rawBody the body as received
   */
  public ZapResponse(T data, int statusCode, HttpHeaders headers, String rawBody) {
    this.data = data;
    this.statusCode = statusCode;
    this.headers = headers;
    this.rawBody = rawBody;
  }

  /**
   * The decoded data.
   *
   * @return the data; null for an operation that answers nothing, or a body that is not JSON (see
   *     {@link #getRawBody()})
   */
  public T getData() {
    return data;
  }

  /**
   * The HTTP status.
   *
   * @return the status
   */
  public int getStatusCode() {
    return statusCode;
  }

  /**
   * The response headers, among them the rate-limit and quota headers.
   *
   * @return the headers
   */
  public HttpHeaders getHeaders() {
    return headers;
  }

  /**
   * A header's first value.
   *
   * @param name the name, in any case
   * @return the value, or null
   */
  public String getHeader(String name) {
    return headers.firstValue(name).orElse(null);
  }

  /**
   * The X-Request-Id: quote it to support.
   *
   * @return the request id, or null
   */
  public String getRequestId() {
    return getHeader("x-request-id");
  }

  /**
   * The body as received.
   *
   * @return the body, empty when there was none
   */
  public String getRawBody() {
    return rawBody;
  }
}
