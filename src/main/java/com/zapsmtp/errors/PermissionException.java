package com.zapsmtp.errors;

import java.net.http.HttpHeaders;

/** 403: the API key may not do this. */
public class PermissionException extends ZapSMTPException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the error of an API answer.
   *
   * @param message the API's message
   * @param statusCode the HTTP status
   * @param code the API's error code, or an empty string
   * @param type the API's error type, or an empty string
   * @param requestId the X-Request-Id header, or null
   * @param headers the response headers
   * @param body the parsed error body, or null
   */
  public PermissionException(String message, int statusCode, String code, String type, String requestId, HttpHeaders headers, Object body) {
    super(message, statusCode, code, type, requestId, headers, body, null);
  }
}
