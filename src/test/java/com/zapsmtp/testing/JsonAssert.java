package com.zapsmtp.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Compares JSON as the API means it: absent equals null, 2 equals 2.0, times by their instant. */
public final class JsonAssert {
  private static final Pattern TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T.*");

  private JsonAssert() {}

  public static void assertJsonEquals(JsonNode want, JsonNode got, String message) {
    assertEquals(String.valueOf(normalize(want)), String.valueOf(normalize(got)), message);
  }

  public static Object normalize(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) {
      return null;
    }
    if (n.isObject()) {
      Map<String, Object> m = new TreeMap<>();
      for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        Object v = normalize(e.getValue());
        if (v != null) {
          m.put(e.getKey(), v);
        }
      }
      return m;
    }
    if (n.isArray()) {
      List<Object> l = new ArrayList<>();
      n.forEach((x) -> l.add(normalize(x)));
      return l;
    }
    if (n.isNumber()) {
      BigDecimal d = n.decimalValue().stripTrailingZeros();
      return d.signum() == 0 ? "0" : d.toPlainString();
    }
    if (n.isTextual() && TIME.matcher(n.textValue()).matches()) {
      try {
        return OffsetDateTime.parse(n.textValue()).toInstant().toString();
      } catch (DateTimeParseException e) {
        return n.textValue();
      }
    }
    return n.isTextual() ? n.textValue() : n.toString();
  }
}
