package com.zapsmtp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.zapsmtp.errors.AuthenticationException;
import com.zapsmtp.errors.ConflictException;
import com.zapsmtp.errors.NotFoundException;
import com.zapsmtp.errors.PermissionException;
import com.zapsmtp.errors.RateLimitException;
import com.zapsmtp.errors.ServerException;
import com.zapsmtp.errors.ValidationException;
import com.zapsmtp.errors.ZapSMTPException;
import java.net.http.HttpHeaders;

/**
 * Maps an error answer to its exception class.
 *
 * <p>Internal: not part of the SDK's public API.
 */
public final class ApiErrors {
  private ApiErrors() {}

  /**
   * The exception for an error answer.
   *
   * @param status the HTTP status
   * @param body the parsed body, or null
   * @param headers the response headers
   * @return the exception
   */
  public static ZapSMTPException of(int status, JsonNode body, HttpHeaders headers) {
    JsonNode error = body == null ? null : body.get("error");
    String code = first(field(error, "code"), field(body, "name"), "");
    String message = first(field(error, "message"), field(body, "message"), "HTTP " + status);
    String type = first(field(error, "type"), null, "");
    String requestId = headers.firstValue("x-request-id").orElse(null);
    Object parsed = Json.toJava(body);
    if (status == 401) {
      return new AuthenticationException(message, status, code, type, requestId, headers, parsed);
    } else if (status == 403) {
      return new PermissionException(message, status, code, type, requestId, headers, parsed);
    } else if (status == 404) {
      return new NotFoundException(message, status, code, type, requestId, headers, parsed);
    } else if (status == 409) {
      return new ConflictException(message, status, code, type, requestId, headers, parsed);
    } else if (status == 400 || status == 413 || status == 422) {
      return new ValidationException(message, status, code, type, requestId, headers, parsed);
    } else if (status == 429) {
      return new RateLimitException(message, status, code, type, requestId, headers, parsed);
    } else if (status >= 500) {
      return new ServerException(message, status, code, type, requestId, headers, parsed);
    }
    return new ZapSMTPException(message, status, code, type, requestId, headers, parsed, null);
  }

  // a field that is present and not null, as text
  private static String field(JsonNode node, String name) {
    JsonNode v = node == null ? null : node.get(name);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static String first(String a, String b, String fallback) {
    return a != null ? a : b != null ? b : fallback;
  }
}
