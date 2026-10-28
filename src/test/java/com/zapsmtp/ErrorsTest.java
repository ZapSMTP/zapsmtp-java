package com.zapsmtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.zapsmtp.errors.ConnectionException;
import com.zapsmtp.errors.WebhookVerificationException;
import com.zapsmtp.errors.ZapSMTPException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ErrorsTest {
  @Test
  void errorsWithoutAnAnswerHaveEmptyFields() {
    ZapSMTPException e = new ZapSMTPException("x");
    assertEquals(0, e.getStatusCode());
    assertEquals("", e.getCode());
    assertEquals("", e.getType());
    assertNull(e.getRequestId());
    assertNull(e.getBody());
    assertEquals(0, e.getHeaders().map().size());
    ZapSMTPException nulls = new ZapSMTPException("x", 500, null, null, "r", null, null, null);
    assertEquals("", nulls.getCode());
    assertEquals("", nulls.getType());
    IOException cause = new IOException("reset");
    assertEquals(cause, new ConnectionException("reset", cause).getCause());
    assertEquals("bad", new WebhookVerificationException("bad", null).getMessage());
  }
}
