package com.zapsmtp.core;

import com.zapsmtp.errors.ValidationException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One API call as the generated services describe it: method, path, query, body, headers.
 *
 * <p>Internal: not part of the SDK's public API.
 */
public final class ApiRequest {
  private final String method;
  private final String path;
  private final Map<String, Object> query;
  private final Object body;
  private final Multipart multipart;
  private final Map<String, String> headers;
  private final boolean idempotent;

  private ApiRequest(Builder b) {
    method = b.method;
    path = b.path;
    query = Collections.unmodifiableMap(new LinkedHashMap<>(b.query));
    body = b.body;
    multipart = b.multipart;
    headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
    idempotent = b.idempotent;
  }

  /**
   * Starts a request.
   *
   * @param method the HTTP method
   * @param path the path below the base URL, its parameters already escaped with {@link #seg}
   * @return a builder
   */
  public static Builder builder(String method, String path) {
    return new Builder(method, path);
  }

  /**
   * Escapes a path parameter the way JavaScript's encodeURIComponent does. An empty value, "." or ".." can be no
   * id: URL normalization would resolve them into another path (escaped too), so they are refused before anything
   * is sent.
   *
   * @param name the parameter's name, for the error message
   * @param value the value
   * @return the escaped segment
   * @throws ValidationException (code invalid_parameter) when the value is null, empty, "." or ".."
   */
  public static String seg(String name, String value) {
    if (value == null || value.isEmpty()) {
      throw invalid(name + " must not be empty");
    }
    if (value.equals(".") || value.equals("..")) {
      throw invalid(name + " must not be \"" + value + "\"");
    }
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")")
        .replace("%7E", "~");
  }

  static ValidationException invalid(String message) {
    return new ValidationException(message, 0, "invalid_parameter", "", null, null, null);
  }

  /**
   * Returns the HTTP method.
   *
   * @return the HTTP method
   */
  public String method() {
    return method;
  }

  /**
   * Returns the path below the base URL.
   *
   * @return the path below the base URL
   */
  public String path() {
    return path;
  }

  /**
   * Returns the query parameters: values, lists (repeated) and maps (key[name]=value).
   *
   * @return the query parameters: values, lists (repeated) and maps (key[name]=value)
   */
  public Map<String, Object> query() {
    return query;
  }

  /**
   * Returns the JSON body, or null.
   *
   * @return the JSON body, or null
   */
  public Object body() {
    return body;
  }

  /**
   * Returns the multipart body, or null.
   *
   * @return the multipart body, or null
   */
  public Multipart multipart() {
    return multipart;
  }

  /**
   * Returns extra headers of this operation; a null value is not sent.
   *
   * @return extra headers of this operation; a null value is not sent
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Returns whether the operation takes an Idempotency-Key, so the SDK sends one and may retry.
   *
   * @return whether the operation takes an Idempotency-Key, so the SDK sends one and may retry
   */
  public boolean idempotent() {
    return idempotent;
  }

  /**
   * The same request with more query parameters (the next page).
   *
   * @param extra the parameters to set
   * @return the new request
   */
  ApiRequest withQuery(Map<String, Object> extra) {
    Builder b = new Builder(method, path);
    b.query.putAll(query);
    b.query.putAll(extra);
    b.body = body;
    b.multipart = multipart;
    b.headers.putAll(headers);
    b.idempotent = idempotent;
    return b.build();
  }

  /** Builds an {@link ApiRequest}. */
  public static final class Builder {
    private final String method;
    private final String path;
    private final Map<String, Object> query = new LinkedHashMap<>();
    private Object body;
    private Multipart multipart;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private boolean idempotent;

    private Builder(String method, String path) {
      this.method = method;
      this.path = path;
    }

    /**
     * Sends parameters in the query.
     *
     * @param params the parameters
     * @param names the names to send, or none for all
     * @return this builder
     */
    public Builder query(ApiParams params, String... names) {
      query.putAll(select(params, names));
      return this;
    }

    /**
     * Sends parameters as the JSON body.
     *
     * @param params the parameters
     * @param names the names to send, or none for all
     * @return this builder
     */
    public Builder body(ApiParams params, String... names) {
      body = select(params, names);
      return this;
    }

    /**
     * Sends a value as the JSON body.
     *
     * @param value the body
     * @return this builder
     */
    public Builder body(Object value) {
      body = Objects.requireNonNull(value, "body");
      return this;
    }

    /**
     * Sends a multipart/form-data body.
     *
     * @param form the form
     * @return this builder
     */
    public Builder multipart(Multipart form) {
      multipart = form;
      return this;
    }

    /**
     * Sets a header of this operation.
     *
     * @param name the name
     * @param value the value; null sends nothing
     * @return this builder
     */
    public Builder header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    /**
     * Marks the operation as taking an Idempotency-Key.
     *
     * @return this builder
     */
    public Builder idempotent() {
      idempotent = true;
      return this;
    }

    /**
     * Returns the request.
     *
     * @return the request
     */
    public ApiRequest build() {
      return new ApiRequest(this);
    }

    private static Map<String, Object> select(ApiParams params, String... names) {
      Map<String, Object> all = Objects.requireNonNull(params, "params").toMap();
      if (names.length == 0) {
        return all;
      }
      Map<String, Object> out = new LinkedHashMap<>();
      for (String n : names) {
        if (all.containsKey(n)) {
          out.put(n, all.get(n));
        }
      }
      return out;
    }
  }
}
