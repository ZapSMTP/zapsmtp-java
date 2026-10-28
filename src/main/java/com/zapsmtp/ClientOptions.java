package com.zapsmtp;

import com.zapsmtp.errors.ZapSMTPException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** The configuration of a {@link ZapSMTP} client. Build it with {@link ZapSMTP#builder()}. */
public final class ClientOptions {
  /** The production API. */
  public static final String DEFAULT_BASE_URL = "https://api.zapsmtp.com/v1";
  /** The per-attempt timeout unless set. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
  /** How many times a failed request that is safe to repeat is retried unless set. */
  public static final int DEFAULT_MAX_RETRIES = 2;

  private final String apiKey;
  private final String baseUrl;
  private final Duration timeout;
  private final int maxRetries;
  private final HttpClient httpClient;
  private final Map<String, String> defaultHeaders;

  private ClientOptions(Builder<?> b) {
    String key = b.apiKey != null ? b.apiKey : b.env.apply("ZAP_API_KEY");
    if (key == null || key.isBlank()) {
      throw new ZapSMTPException("Missing API key: pass it to ZapSMTP.builder().apiKey(...) or set ZAP_API_KEY");
    }
    String base = b.baseUrl != null ? b.baseUrl : b.env.apply("ZAP_BASE_URL");
    apiKey = key;
    baseUrl = (base == null || base.isBlank() ? DEFAULT_BASE_URL : base).replaceAll("/+$", "");
    timeout = b.timeout;
    maxRetries = b.maxRetries;
    httpClient = b.httpClient;
    defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(b.defaultHeaders));
  }

  /**
   * The API key.
   *
   * @return the key
   */
  public String getApiKey() {
    return apiKey;
  }

  /**
   * The base URL, without a trailing slash.
   *
   * @return the URL
   */
  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * The timeout of one attempt, the response body included.
   *
   * @return the timeout
   */
  public Duration getTimeout() {
    return timeout;
  }

  /**
   * How many times a failed request that is safe to repeat is retried.
   *
   * @return the number of retries
   */
  public int getMaxRetries() {
    return maxRetries;
  }

  /**
   * The HTTP client requests are sent with.
   *
   * @return the client, or null for one the SDK makes
   */
  public HttpClient getHttpClient() {
    return httpClient;
  }

  /**
   * Headers sent with every request.
   *
   * @return an unmodifiable map
   */
  public Map<String, String> getDefaultHeaders() {
    return defaultHeaders;
  }

  /**
   * Builds the options of a client. Without an API key or base URL it reads the environment variables
   * {@code ZAP_API_KEY} and {@code ZAP_BASE_URL}.
   *
   * @param <B> the builder's own type
   */
  public abstract static class Builder<B extends Builder<B>> {
    private String apiKey;
    private String baseUrl;
    private Duration timeout = DEFAULT_TIMEOUT;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private HttpClient httpClient;
    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private Function<String, String> env = System::getenv;

    /** For {@link ZapSMTP.Builder}. */
    protected Builder() {}

    /**
     * Sets the API key (default: the ZAP_API_KEY environment variable).
     *
     * @param apiKey the key, zap_...
     * @return this builder
     */
    public final B apiKey(String apiKey) {
      this.apiKey = apiKey;
      return self();
    }

    /**
     * Sets the base URL (default: the ZAP_BASE_URL environment variable, else https://api.zapsmtp.com/v1).
     *
     * @param baseUrl the URL, for example https://api.zapsmtp.com/v1
     * @return this builder
     */
    public final B baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return self();
    }

    /**
     * Sets the timeout of one attempt, the response body included (default 60 s).
     *
     * @param timeout a positive duration
     * @return this builder
     * @throws IllegalArgumentException when it is not positive
     */
    public final B timeout(Duration timeout) {
      if (timeout.isNegative() || timeout.isZero()) {
        throw new IllegalArgumentException("timeout must be positive");
      }
      this.timeout = timeout;
      return self();
    }

    /**
     * Sets how many times a failed request that is safe to repeat is retried (default 2).
     *
     * @param maxRetries zero or more
     * @return this builder
     * @throws IllegalArgumentException when it is negative
     */
    public final B maxRetries(int maxRetries) {
      if (maxRetries < 0) {
        throw new IllegalArgumentException("maxRetries must not be negative");
      }
      this.maxRetries = maxRetries;
      return self();
    }

    /**
     * Sends requests with this HTTP client (proxies, TLS, executors). Its own timeouts still apply; the SDK's
     * timeout bounds each attempt.
     *
     * @param httpClient the client
     * @return this builder
     */
    public final B httpClient(HttpClient httpClient) {
      this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
      return self();
    }

    /**
     * Adds a header sent with every request.
     *
     * @param name the name
     * @param value the value
     * @return this builder
     */
    public final B defaultHeader(String name, String value) {
      defaultHeaders.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return self();
    }

    final B env(Function<String, String> env) {
      this.env = env;
      return self();
    }

    /**
     * Checks and freezes the settings.
     *
     * @return the options
     * @throws ZapSMTPException when there is no API key
     */
    protected final ClientOptions buildOptions() {
      return new ClientOptions(this);
    }

    @SuppressWarnings("unchecked")
    private B self() {
      return (B) this;
    }
  }
}
