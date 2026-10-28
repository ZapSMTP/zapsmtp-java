package com.zapsmtp.errors;

/**
 * No answer from the API: the connection failed, broke, timed out, or the thread was interrupted. When the
 * status and headers arrived but the body could not be read, {@link #getStatusCode()}, {@link #getHeaders()} and
 * {@link #getRequestId()} give them; otherwise the status is 0.
 */
public class ConnectionException extends ZapSMTPException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates a connection error.
   *
   * @param message what went wrong
   * @param cause the underlying failure, or null
   */
  public ConnectionException(String message, Throwable cause) {
    super(message, 0, "", "", null, null, null, cause);
  }

  /**
   * Creates the error of an answer whose body could not be read: the status and headers arrived.
   *
   * @param message what went wrong
   * @param cause the underlying failure
   * @param statusCode the HTTP status
   * @param headers the response headers
   */
  public ConnectionException(String message, Throwable cause, int statusCode, java.net.http.HttpHeaders headers) {
    super(message, statusCode, "", "", headers.firstValue("x-request-id").orElse(null), headers, null, cause);
  }
}
