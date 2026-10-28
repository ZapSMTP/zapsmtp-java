package com.zapsmtp;

import com.zapsmtp.params.OutgoingAttachment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * Builds attachments from files and bytes: the content is sent base64-encoded, and the content type follows the
 * file name's extension when it is a common one.
 *
 * <pre>{@code
 * OutgoingAttachment invoice = Attachments.fromFile(Path.of("invoice.pdf"));
 * OutgoingAttachment logo = Attachments.fromBytes("logo.png", bytes).toBuilder().contentId("logo").build();
 * }</pre>
 */
public final class Attachments {
  private static final Map<String, String> TYPES = Map.ofEntries(
      Map.entry("pdf", "application/pdf"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
      Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
      Map.entry("svg", "image/svg+xml"), Map.entry("txt", "text/plain"), Map.entry("csv", "text/csv"),
      Map.entry("html", "text/html"), Map.entry("ics", "text/calendar"), Map.entry("json", "application/json"),
      Map.entry("xml", "application/xml"), Map.entry("zip", "application/zip"), Map.entry("doc", "application/msword"),
      Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
      Map.entry("xls", "application/vnd.ms-excel"),
      Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

  private Attachments() {}

  /**
   * An attachment from bytes.
   *
   * @param filename the name the recipient sees; its extension gives the content type
   * @param content the file's bytes
   * @return the attachment
   */
  public static OutgoingAttachment fromBytes(String filename, byte[] content) {
    OutgoingAttachment.Builder b = OutgoingAttachment.builder()
        .filename(filename)
        .content(Base64.getEncoder().encodeToString(content));
    String type = contentTypeOf(filename);
    if (type != null) {
      b.contentType(type);
    }
    return b.build();
  }

  /**
   * An attachment from text, encoded as UTF-8.
   *
   * @param filename the name the recipient sees; its extension gives the content type
   * @param content the text
   * @return the attachment
   */
  public static OutgoingAttachment fromString(String filename, String content) {
    return fromBytes(filename, content.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * An attachment read from a file, named after it.
   *
   * @param path the file
   * @return the attachment
   * @throws UncheckedIOException when the file cannot be read
   */
  public static OutgoingAttachment fromFile(Path path) {
    try {
      return fromBytes(String.valueOf(path.getFileName()), Files.readAllBytes(path));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The content type of a file name's extension, for the common types (PDF, images, text, office documents).
   *
   * @param filename the file name
   * @return the type, or null when the extension is unknown
   */
  public static String contentTypeOf(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? null : TYPES.get(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
  }
}
