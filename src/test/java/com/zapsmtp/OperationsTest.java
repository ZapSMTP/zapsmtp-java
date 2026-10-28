package com.zapsmtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.zapsmtp.core.Json;
import com.zapsmtp.models.TemplateAsset;
import com.zapsmtp.testing.Cases;
import com.zapsmtp.testing.TestServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** The overloads the conformance cases cannot reach: without the optional parameters, and file uploads. */
class OperationsTest {
  private static TestServer server;
  private static ZapSMTP zap;

  @BeforeAll
  static void start() throws IOException {
    List<JsonNode> cases = Cases.cases();
    server = new TestServer().respond((seen) -> {
      for (JsonNode c : cases) {
        if (c.path("expect").path("method").asText().equals(seen.method)
            && ("/v1" + c.path("expect").path("path").asText()).equals(seen.path)) {
          JsonNode body = c.path("response").path("body");
          return body.isNull() ? TestServer.text(c.path("response").path("status").asInt(), "")
              : TestServer.json(c.path("response").path("status").asInt(), body.toString());
        }
      }
      return TestServer.json(404, "{}");
    });
    zap = ZapSMTP.builder().apiKey("zap_test").baseUrl(server.url()).maxRetries(0).build();
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  @TestFactory
  Stream<DynamicTest> overloadsWithoutOptionalParameters() throws ReflectiveOperationException {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : Cases.cases()) {
      Object service = Cases.service(zap, c);
      for (Method m : Cases.overloads(service, c)) {
        if (Cases.takesAll(m, c)) {
          continue;
        }
        tests.add(DynamicTest.dynamicTest(c.path("id").asText() + " " + m.toGenericString(), () -> {
          Object[] result = Cases.invoke(service, m, Cases.args(m, c, RequestOptions.none()));
          TestServer.Seen s = server.last();
          JsonNode expect = c.path("expect");
          assertEquals(expect.path("method").asText(), s.method);
          assertEquals("/v1" + expect.path("path").asText(), s.path);
          assertNull(s.query, "no query");
          if (c.path("call").has("items")) {
            assertEquals(expect.path("body"), Json.MAPPER.readTree(s.body));
            assertNull(s.header("x-batch-validation"));
          } else if (expect.path("body").isNull()) {
            assertEquals("", s.text());
          } else {
            assertEquals("{}", s.text());
          }
          assertTrue(c.path("response").path("body").isNull() == (result[0] == null));
        }));
      }
    }
    assertTrue(tests.size() > 20, "operations with optional parameters: " + tests.size());
    return tests.stream();
  }

  @Test
  void uploadsAnImageFileAsMultipart(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("logo.png");
    Files.write(file, new byte[] {1, 2, 3});
    TemplateAsset a = zap.templateAssets().uploadFile(new byte[] {1, 2, 3}, "logo.png");
    assertEquals("template_asset", a.getObject());
    TestServer.Seen s = server.last();
    assertEquals("POST", s.method);
    assertEquals("/v1/template-assets", s.path);
    String type = s.header("Content-Type");
    assertTrue(type.startsWith("multipart/form-data; boundary=zapsmtp-"), type);
    String boundary = type.substring(type.indexOf('=') + 1);
    String body = s.text();
    assertEquals("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"logo.png\"\r\n"
        + "Content-Type: image/png\r\n\r\n\u0001\u0002\u0003\r\n--" + boundary + "\r\nContent-Disposition: form-data; "
        + "name=\"name\"\r\n\r\nlogo.png\r\n--" + boundary + "--\r\n", body);

    zap.templateAssets().uploadFile(new byte[] {1}, null);
    assertTrue(server.last().text().contains("filename=\"image\"\r\nContent-Type: application/octet-stream"));
    assertTrue(!server.last().text().contains("name=\"name\""));
    zap.templateAssets().uploadFile(new byte[] {1}, "a\"b\r\n.gif", RequestOptions.none());
    assertTrue(server.last().text().contains("filename=\"a%22b%0D%0A.gif\"\r\nContent-Type: image/gif"));

    zap.templateAssets().uploadFile(file);
    assertTrue(server.last().text().contains("filename=\"logo.png\""));
    assertTrue(!server.last().text().contains("name=\"name\""));
    zap.templateAssets().uploadFile(file, "brand");
    assertTrue(server.last().text().contains("name=\"name\"\r\n\r\nbrand\r\n"));
    zap.templateAssets().uploadFile(file, null, RequestOptions.none());
    assertThrows(UncheckedIOException.class, () -> zap.templateAssets().uploadFile(dir.resolve("missing.png")));
  }
}
