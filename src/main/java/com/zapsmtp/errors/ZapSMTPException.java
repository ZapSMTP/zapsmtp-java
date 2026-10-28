package com.zapsmtp.errors;

import java.net.http.HttpHeaders;
import java.util.Map;

/**
 * An error answer of the ZapSMTP API, or no answer at all ({@link ConnectionException}).
 *
 * <p>The subclasses name the HTTP status: {@link AuthenticationException} (401), {@link PermissionException} (403),
 * {@link NotFoundException} (404), {@link ConflictException} (409), {@link ValidationException} (400, 413, 422),
 * {@link RateLimitException} (429) and {@link ServerException} (5xx). Any other status is a plain
 * {@code ZapSMTPException}.
 */
public class ZapSMTPException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private static final HttpHeaders NO_HEADERS = HttpHeaders.of(Map.of(), (name, value) -> true);

  /** The HTTP status, 0 when there was no answer. */
  private final int statusCode;
  /** The API's error code, or an empty string. */
  private final String code;
  /** The API's error type, or an empty string. */
  private final String type;
  /** The X-Request-Id of the answer, or null. */
  private final String requestId;
  private final transient HttpHeaders headers;
  private final transient Object body;

  /**
   * Creates an error.
   *
   * @param message the message
   * @param statusCode the HTTP status, 0 when there was no answer
   * @param code the API's error code, or an empty string
   * @param type the API's error type, or an empty string
   * @param requestId the X-Request-Id header, or null
   * @param headers the response headers, or null when there was no answer
   * @param body the parsed error body, or null
   * @param cause the underlying failure, or null
   */
  public ZapSMTPException(String message, int statusCode, String code, String type, String requestId, HttpHeaders headers,
      Object body, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
    this.code = code == null ? "" : code;
    this.type = type == null ? "" : type;
    this.requestId = requestId;
    this.headers = headers == null ? NO_HEADERS : headers;
    this.body = body;
  }

  /**
   * Creates an error that is not an API answer, such as a missing API key.
   *
   * @param message the message
   */
  public ZapSMTPException(String message) {
    this(message, 0, "", "", null, null, null, null);
  }

  /**
   * The HTTP status.
   *
   * @return the status, or 0 when there was no answer
   */
  public int getStatusCode() {
    return statusCode;
  }

  /**
   * The API's error code, for example {@code invalid_parameter} or {@code rate_limit_exceeded}.
   *
   * @return the code, or an empty string
   */
  public String getCode() {
    return code;
  }

  /**
   * The API's error type, for example {@code invalid_request_error}.
   *
   * @return the type, or an empty string
   */
  public String getType() {
    return type;
  }

  /**
   * The X-Request-Id of the answer: quote it to support.
   *
   * @return the request id, or null
   */
  public String getRequestId() {
    return requestId;
  }

  /**
   * The response headers.
   *
   * @return the headers, empty when there was no answer
   */
  public HttpHeaders getHeaders() {
    return headers;
  }

  /**
   * The error body: JSON as maps, lists and values, or the text when it is not JSON.
   *
   * @return the body, or null when there was none
   */
  public Object getBody() {
    return body;
  }
}
