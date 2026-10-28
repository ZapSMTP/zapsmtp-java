package com.zapsmtp.core;

import com.zapsmtp.Attachments;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A multipart/form-data body with one file and an optional name field.
 *
 * <p>Internal: not part of the SDK's public API.
 */
public final class Multipart {
  private final String boundary = "zapsmtp-" + UUID.randomUUID();
  private final byte[] bytes;

  private Multipart(String field, byte[] content, String filename, String name) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String type = Attachments.contentTypeOf(filename);
    write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + escape(field) + "\"; filename=\""
        + escape(filename) + "\"\r\nContent-Type: " + (type == null ? "application/octet-stream" : type) + "\r\n\r\n");
    out.writeBytes(content);
    write(out, "\r\n");
    if (name != null) {
      write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n" + name + "\r\n");
    }
    write(out, "--" + boundary + "--\r\n");
    bytes = out.toByteArray();
  }

  /**
   * A file from bytes.
   *
   * @param field the file's form field
   * @param content the file
   * @param name the name field, or null to send none; it is also the file name ("image" when null)
   * @return the form
   */
  public static Multipart of(String field, byte[] content, String name) {
    return new Multipart(field, content, name == null ? "image" : name, name);
  }

  /**
   * A file read from disk.
   *
   * @param field the file's form field
   * @param file the file; its name is the file name
   * @param name the name field, or null to send none
   * @return the form
   * @throws UncheckedIOException when the file cannot be read
   */
  public static Multipart of(String field, Path file, String name) {
    try {
      return new Multipart(field, Files.readAllBytes(file), String.valueOf(file.getFileName()), name);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns the Content-Type header, with the boundary.
   *
   * @return the Content-Type header, with the boundary
   */
  public String contentType() {
    return "multipart/form-data; boundary=" + boundary;
  }

  /**
   * Returns the encoded body.
   *
   * @return the encoded body
   */
  public byte[] bytes() {
    return bytes.clone();
  }

  private static void write(ByteArrayOutputStream out, String s) {
    out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
  }

  // the escaping of the HTML form encoding
  private static String escape(String s) {
    return s.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
  }
}
