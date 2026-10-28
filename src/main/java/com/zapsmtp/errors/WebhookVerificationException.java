package com.zapsmtp.errors;

/** A webhook request whose Zap-Signature does not prove it came from ZapSMTP, or whose body is not an event. */
public class WebhookVerificationException extends ZapSMTPException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates a verification error.
   *
   * @param message why the request was refused
   * @param cause the underlying failure, or null
   */
  public WebhookVerificationException(String message, Throwable cause) {
    super(message, 0, "", "", null, null, null, cause);
  }
}
