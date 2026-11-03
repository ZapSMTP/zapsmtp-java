package com.zapsmtp;

import static com.zapsmtp.testing.JsonAssert.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.zapsmtp.core.ApiParams;
import com.zapsmtp.core.Json;
import com.zapsmtp.testing.Cases;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every API operation, through every overload that takes the case's arguments, against the conformance mock
 * (sdks/conformance/server.mjs), which checks the exact request it gets. The mock is ZAP_CONFORMANCE_URL when
 * set (sdks/scripts/docker-test.sh starts one), else started with node.
 */
class ConformanceTest {
  private static Process server;
  private static ZapSMTP zap;

  @BeforeAll
  static void start() throws Exception {
    String url = System.getenv("ZAP_CONFORMANCE_URL");
    if (url == null || url.isEmpty()) {
      server = new ProcessBuilder("node", Cases.sdks().resolve("conformance/server.mjs").toString(), "0")
          .redirectErrorStream(true).start();
      BufferedReader out = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
      Matcher m = Pattern.compile("listening (\\d+)").matcher(String.valueOf(out.readLine()));
      if (!m.find()) {
        fail("the conformance server did not start: run with ZAP_CONFORMANCE_URL or node on the PATH");
      }
      url = "http://127.0.0.1:" + m.group(1) + "/v1";
    }
    zap = ZapSMTP.builder().apiKey("zap_test_conformance").baseUrl(url).maxRetries(0).build();
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.destroy();
    }
  }

  @Test
  void coversEveryOperationOnce() {
    List<JsonNode> cases = Cases.cases();
    Set<String> ids = new HashSet<>();
    cases.forEach((c) -> ids.add(c.path("id").asText()));
    assertEquals(cases.size(), ids.size());
    assertEquals(119, cases.size());
  }

  @TestFactory
  Stream<DynamicContainer> conformance() {
    return Cases.cases().stream().map((c) -> DynamicContainer.dynamicContainer(c.path("id").asText(), calls(c)));
  }

  private static Stream<DynamicTest> calls(JsonNode c) {
    List<DynamicTest> tests = new ArrayList<>();
    tests.add(DynamicTest.dynamicTest("params", () -> checkParams(c)));
    Object service;
    try {
      service = Cases.service(zap, c);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    List<Method> methods = Cases.overloads(service, c);
    int full = 0;
    for (Method m : methods) {
      if (!Cases.takesAll(m, c)) {
        continue;
      }
      full++;
      tests.add(DynamicTest.dynamicTest(m.toGenericString(), () -> {
        RequestOptions options = RequestOptions.builder().timeout(Duration.ofSeconds(30)).header("X-Conformance", "1").build();
        Object[] result = Cases.invoke(service, m, Cases.args(m, c, options));
        checkResult(c, result[0], (ZapResponse<?>) result[1], m.getName().endsWith("WithResponse"));
      }));
    }
    int overloads = full;
    tests.add(DynamicTest.dynamicTest("four overloads take every argument", () -> assertEquals(4, overloads)));
    return tests.stream();
  }

  private static void checkResult(JsonNode c, Object data, ZapResponse<?> response, boolean withResponse) {
    JsonNode body = c.path("response").path("body");
    if (withResponse) {
      assertNotNull(response);
      assertEquals(c.path("response").path("status").asInt(), response.getStatusCode());
      assertEquals("req_conformance", response.getRequestId());
    }
    if (body.isNull()) {
      assertNull(data);
    } else if (data instanceof Page) {
      Page<?> page = (Page<?>) data;
      assertJsonEquals(body.path("data"), Json.MAPPER.valueToTree(page.getData()), "page data");
      assertEquals(body.path("has_more").asBoolean(false), page.hasMore());
      assertEquals(body.path("next_cursor").isTextual() ? body.path("next_cursor").asText() : null, page.getNextCursor());
    } else {
      assertNotNull(data);
      assertJsonEquals(body, Json.MAPPER.valueToTree(data), "response");
    }
  }

  // the typed parameters hold what the case says, through every getter, toBuilder and the varargs setters
  private static void checkParams(JsonNode c) throws Exception {
    JsonNode call = c.path("call");
    if (call.has("items")) {
      for (JsonNode item : call.path("items")) {
        check(Json.MAPPER.convertValue(item, com.zapsmtp.params.SendEmailParams.class), item);
      }
      check(Json.MAPPER.convertValue(call.path("options"), com.zapsmtp.params.SendBatchOptions.class), call.path("options"));
    } else if (!call.path("params").isNull()) {
      Object service = Cases.service(zap, c);
      for (Method m : Cases.overloads(service, c)) {
        for (Class<?> t : m.getParameterTypes()) {
          if (ApiParams.class.isAssignableFrom(t)) {
            check((ApiParams) Json.MAPPER.convertValue(call.path("params"), t), call.path("params"));
            return;
          }
        }
      }
      fail("no parameters type");
    }
  }

  static void check(ApiParams params, JsonNode json) throws Exception {
    assertJsonEquals(json, Json.MAPPER.valueToTree(params), "parameters");
    Map<String, Object> values = params.toMap();
    assertEquals(json.size(), values.size(), "every parameter set");
    Class<?> cls = params.getClass();
    Class<?> builderCls = Class.forName(cls.getName() + "$Builder");
    for (Method g : cls.getDeclaredMethods()) {
      if (!g.getName().startsWith("get") || g.getParameterCount() != 0 || !java.lang.reflect.Modifier.isPublic(g.getModifiers())) {
        continue;
      }
      String name = apiName(builderCls, g.getName().substring(3));
      Object v = g.invoke(params);
      assertJsonEquals(json.path(name), Json.MAPPER.valueToTree(v), cls.getSimpleName() + "." + g.getName());
      if (v instanceof ApiParams) {
        check((ApiParams) v, json.path(name));
      } else if (v instanceof List) {
        int i = 0;
        for (Object x : (List<?>) v) {
          if (x instanceof ApiParams) {
            check((ApiParams) x, json.path(name).get(i));
          }
          i++;
        }
      }
    }
    assertEquals(builderCls, cls.getMethod("builder").invoke(null).getClass());
    Object builder = cls.getMethod("toBuilder").invoke(params);
    assertEquals(params, builderCls.getMethod("build").invoke(builder), "toBuilder().build()");
    assertEquals(params.hashCode(), builderCls.getMethod("build").invoke(builder).hashCode());
    assertTrue(params.toString().startsWith(cls.getSimpleName() + "{"));
    // each varargs setter sets what its list twin sets
    for (Method s : builderCls.getDeclaredMethods()) {
      if (s.getParameterCount() == 1 && s.getParameterTypes()[0].isArray()) {
        String name = s.getAnnotation(JsonProperty.class) == null ? jsonName(builderCls, s.getName()) : null;
        Object array = Json.MAPPER.convertValue(json.path(name), s.getParameterTypes()[0]);
        Object b = cls.getMethod("toBuilder").invoke(params);
        b = s.invoke(b, array);
        assertEquals(params, builderCls.getMethod("build").invoke(b), cls.getSimpleName() + "." + s.getName() + "(...)");
      }
    }
  }

  // the API name of a getter: the @JsonProperty of the builder setter of the same field
  private static String apiName(Class<?> builderCls, String pascal) {
    String setter = Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    for (Method s : builderCls.getDeclaredMethods()) {
      JsonProperty p = s.getAnnotation(JsonProperty.class);
      if (p != null && (s.getName().equals(setter) || s.getName().equals(setter + "Value"))) {
        return p.value();
      }
    }
    throw new AssertionError("no setter for get" + pascal + " in " + builderCls.getName());
  }

  private static String jsonName(Class<?> builderCls, String setter) {
    for (Method s : builderCls.getDeclaredMethods()) {
      JsonProperty p = s.getAnnotation(JsonProperty.class);
      if (p != null && s.getName().equals(setter)) {
        return p.value();
      }
    }
    throw new AssertionError("no list twin of " + setter);
  }
}
