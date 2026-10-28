package com.zapsmtp.core;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * When a failed request is sent again, and after how long (docs/DESIGN-sdks.md, Retries).
 *
 * <p>Internal: not part of the SDK's public API.
 */
public final class Retry {
  private static final Set<Integer> RETRY_STATUSES = Set.of(408, 429, 500, 502, 503, 504);
  private static final Set<String> NO_RETRY_429 =
      Set.of("monthly_quota_exceeded", "daily_quota_exceeded", "thread_reply_limit");
  private static final Pattern NUMBER = Pattern.compile("\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?\\s*");
  private static final long MAX_WAIT_MS = 60_000;

  private Retry() {}

  /**
   * Whether an error answer is worth another attempt.
   *
   * @param status the HTTP status
   * @param code the API's error code
   * @param safe whether the request may be repeated (GET, PUT, DELETE, or it carries an Idempotency-Key)
   * @return true to retry
   */
  public static boolean retryable(int status, String code, boolean safe) {
    if (status == 429) {
      return !NO_RETRY_429.contains(code); // refused before any work: always safe
    }
    if (status == 409) {
      return code.equals("idempotency_request_in_progress");
    }
    return safe && RETRY_STATUSES.contains(status);
  }

  /**
   * How long to wait before attempt number {@code attempt + 1}: Retry-After up to 60 s, else exponential backoff
   * from 0.5 s, capped at 8 s, with jitter.
   *
   * @param attempt the attempt that failed, from 0
   * @param retryAfter what the API asked for, or null
   * @return the wait
   */
  public static Duration backoff(int attempt, Duration retryAfter) {
    if (retryAfter != null) {
      return Duration.ofMillis(Math.min(retryAfter.toMillis(), MAX_WAIT_MS));
    }
    long base = 500L << Math.min(attempt, 4);
    return Duration.ofMillis(base / 2 + (long) (ThreadLocalRandom.current().nextDouble() * (base / 2)));
  }

  /**
   * Reads Retry-After: seconds, or an HTTP date.
   *
   * @param headers the response headers
   * @param clock the clock an HTTP date is measured against
   * @return the wait (never negative, at most 10^12 s), or null when the header is absent or unreadable; never
   *     throws
   */
  public static Duration retryAfter(HttpHeaders headers, Clock clock) {
    String v = headers.firstValue("retry-after").orElse("");
    if (v.isEmpty()) {
      return null;
    }
    if (NUMBER.matcher(v).matches()) {
      double seconds = Math.max(0, Double.parseDouble(v.trim()));
      return Duration.ofMillis(Math.round(Math.min(seconds, 1e12) * 1000));
    }
    try {
      // the day of the week is not checked, as browsers and Node do not
      String date = v.trim().replaceFirst("^[A-Za-z]{3},\\s*", "");
      ZonedDateTime at = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME);
      Duration d = Duration.between(clock.instant(), at.toInstant());
      return d.isNegative() ? Duration.ZERO : d; // a 4-digit year: at most about 2.5e11 s
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
