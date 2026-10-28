package com.zapsmtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zapsmtp.params.OutgoingAttachment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentsTest {
  @Test
  void encodesBytesAndTextAndGuessesTheContentType() {
    OutgoingAttachment a = Attachments.fromBytes("a.pdf", new byte[] {1, 2});
    assertEquals("a.pdf", a.getFilename());
    assertEquals("AQI=", a.getContent());
    assertEquals("application/pdf", a.getContentType());
    OutgoingAttachment n = Attachments.fromString("notes", "hi").toBuilder().contentType("text/plain").contentId("n1").build();
    assertEquals("aGk=", n.getContent());
    assertEquals("text/plain", n.getContentType());
    assertEquals("n1", n.getContentId());
    assertNull(Attachments.fromBytes("x.unknown", new byte[1]).getContentType());
    assertEquals("image/jpeg", Attachments.contentTypeOf("photo.JPEG"));
    assertNull(Attachments.contentTypeOf("README"));
  }

  @Test
  void readsAFile(@TempDir Path dir) throws IOException {
    Path f = dir.resolve("Report.PNG");
    Files.write(f, new byte[] {(byte) 137, 80});
    OutgoingAttachment a = Attachments.fromFile(f);
    assertEquals("Report.PNG", a.getFilename());
    assertEquals("iVA=", a.getContent());
    assertEquals("image/png", a.getContentType());
    assertThrows(UncheckedIOException.class, () -> Attachments.fromFile(dir.resolve("missing.pdf")));
  }
}
