package com.zapsmtp;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Options of one call, passed as its last argument:
 * {@code RequestOptions.builder().idempotencyKey("order-1042").timeout(Duration.ofSeconds(10)).build()}.
 */
public final class RequestOptions {
  private static final RequestOptions NONE = builder().build();

  private final String idempotencyKey;
  private final Map<String, String> headers;
  private final Duration timeout;
  private final Integer maxRetries;

  private RequestOptions(Builder b) {
    idempotencyKey = b.idempotencyKey;
    headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
    timeout = b.timeout;
    maxRetries = b.maxRetries;
  }

  /**
   * No options: the client's settings.
   *
   * @return the empty options
   */
  public static RequestOptions none() {
    return NONE;
  }

  /**
   * Starts building options.
   *
   * @return a builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The Idempotency-Key sent where the API takes one.
   *
   * @return the key, or null for one the SDK makes (a random UUID, the same on every retry)
   */
  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  /**
   * Headers added to this call.
   *
   * @return an unmodifiable map
   */
  public Map<String, String> getHeaders() {
    return headers;
  }

  /**
   * This call's timeout of one attempt.
   *
   * @return the timeout, or null for the client's
   */
  public Duration getTimeout() {
    return timeout;
  }

  /**
   * This call's number of retries.
   *
   * @return the number, or null for the client's
   */
  public Integer getMaxRetries() {
    return maxRetries;
  }

  /** Builds {@link RequestOptions}. */
  public static final class Builder {
    private String idempotencyKey;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Duration timeout;
    private Integer maxRetries;

    private Builder() {}

    /**
     * Sets the Idempotency-Key of a send ({@code emails().send}, {@code sendBatch}, {@code receiving().reply}).
     * Other operations do not take one.
     *
     * @param idempotencyKey the key, for example your order id
     * @return this builder
     */
    public Builder idempotencyKey(String idempotencyKey) {
      this.idempotencyKey = idempotencyKey;
      return this;
    }

    /**
     * Adds a header to this call.
     *
     * @param name the name
     * @param value the value
     * @return this builder
     */
    public Builder header(String name, String value) {
      headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /**
     * Sets this call's timeout of one attempt.
     *
     * @param timeout a positive duration
     * @return this builder
     * @throws IllegalArgumentException when it is not positive
     */
    public Builder timeout(Duration timeout) {
      if (timeout.isNegative() || timeout.isZero()) {
        throw new IllegalArgumentException("timeout must be positive");
      }
      this.timeout = timeout;
      return this;
    }

    /**
     * Sets this call's number of retries.
     *
     * @param maxRetries zero or more
     * @return this builder
     * @throws IllegalArgumentException when it is negative
     */
    public Builder maxRetries(int maxRetries) {
      if (maxRetries < 0) {
        throw new IllegalArgumentException("maxRetries must not be negative");
      }
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public RequestOptions build() {
      return new RequestOptions(this);
    }
  }
}
