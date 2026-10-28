package com.zapsmtp;

import static com.zapsmtp.testing.JsonAssert.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zapsmtp.core.ApiModel;
import com.zapsmtp.core.ApiParams;
import com.zapsmtp.core.Json;
import com.zapsmtp.testing.Cases;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Every model of sdks/spec/ir.json decodes a value for each of its fields and gives it back through its getters,
 * also the models no conformance response fills.
 */
class ModelsTest {
  private static final JsonNode IR = Cases.read("spec/ir.json");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private static JsonNode sample(JsonNode t, int depth) {
    switch (t.path("k").asText()) {
      case "string":
        return F.textNode("text-1");
      case "datetime":
        return F.textNode("2026-01-02T03:04:05.123+02:00");
      case "integer":
        return F.numberNode(2);
      case "number":
        return F.numberNode(1.5);
      case "boolean":
        return F.booleanNode(true);
      case "any":
        return F.objectNode().put("key", "value");
      case "strings":
        return F.arrayNode().add("a@example.com");
      case "array":
        return F.arrayNode().add(sample(t.path("of"), depth + 1));
      case "map":
        return F.objectNode().set("key", sample(t.path("of"), depth + 1));
      case "ref":
        return object(t.path("ref").asText(), depth + 1);
      default:
        throw new AssertionError("type " + t);
    }
  }

  private static ObjectNode object(String name, int depth) {
    ObjectNode o = F.objectNode();
    JsonNode def = IR.path("models").has(name) ? IR.path("models").path(name) : IR.path("inputs").path(name);
    if (depth > 6) {
      return o;
    }
    for (JsonNode f : def.path("fields")) {
      JsonNode e = f.path("enum");
      o.set(f.path("name").asText(), e.isArray() ? e.get(0) : sample(f.path("type"), depth));
    }
    return o;
  }

  @TestFactory
  Stream<DynamicTest> everyModelDecodesEveryField() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = IR.path("models").fields(); it.hasNext(); ) {
      String name = it.next().getKey();
      tests.add(DynamicTest.dynamicTest(name, () -> {
        Class<?> cls = Class.forName("com.zapsmtp.models." + name);
        ObjectNode json = object(name, 0);
        json.put("field_from_the_future", "kept");
        Object model = Json.MAPPER.convertValue(json, cls);
        assertNotNull(model);
        assertJsonEquals(json, Json.MAPPER.valueToTree(model), name);
        assertEquals("kept", ((ApiModel) model).getAdditionalProperties().get("field_from_the_future"));
        assertEquals(model, Json.MAPPER.convertValue(json, cls));
      }));
    }
    assertEquals(IR.path("models").size(), tests.size());
    return tests.stream();
  }

  @TestFactory
  Stream<DynamicTest> everyInputTypeDecodesEveryField() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = IR.path("inputs").fields(); it.hasNext(); ) {
      String name = it.next().getKey();
      tests.add(DynamicTest.dynamicTest(name, () -> {
        Class<?> cls = Class.forName("com.zapsmtp.params." + name);
        ObjectNode json = object(name, 0);
        ApiParams params = (ApiParams) Json.MAPPER.convertValue(json, cls);
        ConformanceTest.check(params, json);
      }));
    }
    return tests.stream();
  }

}
