package com.zapsmtp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.zapsmtp.models.Attachment;
import com.zapsmtp.models.Email;
import com.zapsmtp.models.Webhook;
import com.zapsmtp.params.OutgoingAttachment;
import com.zapsmtp.params.Tag;
import com.zapsmtp.params.TemplatesCreateParams;
import com.zapsmtp.params.VariableSpec;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Decoding never fails on a surprise; models and parameters behave as values. */
class JsonTest {
  private static <T> T decode(String json, Class<T> type) {
    return Json.decode(Json.parse(json), Json.MAPPER.constructType(type));
  }

  @Test
  void keepsUnknownFieldsAndOffsets() {
    Email e = decode("{\"id\":\"e1\",\"created_at\":\"2026-10-21T15:04:10.902+02:00\",\"brand_new\":{\"x\":[1,2]}}", Email.class);
    assertEquals("e1", e.getId());
    assertEquals(OffsetDateTime.parse("2026-10-21T15:04:10.902+02:00"), e.getCreatedAt());
    assertEquals(Map.of("x", List.of(1, 2)), e.getAdditionalProperties().get("brand_new"));
    assertThrows(UnsupportedOperationException.class, () -> e.getAdditionalProperties().put("a", 1));
  }

  @Test
  void anUnknownKeyNeverOverwritesAKnownField() {
    Email e = decode("{\"id\":\"e1\",\"ID\":\"x\",\"Id\":\"y\",\"createdAt\":\"z\",\"additionalProperties\":{\"id\":\"w\"}}", Email.class);
    assertEquals("e1", e.getId());
    assertNull(e.getCreatedAt());
    assertEquals("x", e.getAdditionalProperties().get("ID"));
    assertEquals("e1", Json.MAPPER.valueToTree(e).path("id").asText());
  }

  @Test
  void turnsValuesOfTheWrongTypeIntoNull() {
    Email e = decode("{\"id\":{\"nested\":true},\"created_at\":\"not a time\",\"events\":[\"text\",7,{\"type\":\"sent\"}],"
        + "\"to\":\"one@example.org\",\"test\":[1,2],\"recipients\":{\"a\":1}}", Email.class);
    assertNull(e.getId());
    assertNull(e.getCreatedAt());
    assertEquals(3, e.getEvents().size());
    assertNull(e.getEvents().get(0));
    assertNull(e.getEvents().get(1));
    assertEquals(List.of("one@example.org"), e.getTo());
    assertNull(e.getTest());
    assertThrows(UnsupportedOperationException.class, () -> e.getTo().add("x"));
    assertNull(decode("\"just text\"", Email.class));
    assertNull(decode("[1,2]", Email.class));
    Webhook w = decode("{\"filter\":{\"actions\":[\"a\"]}}", Webhook.class);
    assertEquals(List.of("a"), w.getFilter().getActions());
    assertNull(decode("{\"name\":\"x\"}", VariableSpec.class)); // a required field missing
  }

  @Test
  void returnsNullWhenNothingCanBeDecoded() {
    // an int parameter out of range fails the whole object (responses use Long, which is lenient)
    assertNull(decode("{\"limit\":99999999999}", com.zapsmtp.params.EmailsListParams.class));
    assertNull(Json.decode(null, Json.MAPPER.constructType(Email.class)));
  }

  @Test
  void parsesBodies() {
    assertNull(Json.parse(""));
    assertNull(Json.parse(" \n"));
    assertTrue(Json.parse("plain").isTextual());
    assertEquals(2, Json.parse("{\"a\":1,\"b\":2}").size());
    assertNull(Json.toJava(null));
    assertEquals(Map.of("a", 1), Json.toJava(Json.parse("{\"a\":1}")));
  }

  @Test
  void refusesABodyThatCannotBeWritten() {
    Object bad = new Object() {
      @SuppressWarnings("unused")
      public String getValue() {
        throw new IllegalStateException("no");
      }
    };
    TemplatesCreateParams p = TemplatesCreateParams.builder().name("n").design(bad).build();
    assertThrows(IllegalArgumentException.class, () -> Json.write(p));
  }

  @Test
  void modelsAreValues() {
    Email a = decode("{\"id\":\"e1\",\"x\":1}", Email.class);
    Email b = decode("{\"id\":\"e1\",\"x\":1}", Email.class);
    Email c = decode("{\"id\":\"e2\"}", Email.class);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
    assertNotEquals(a, decode("{\"id\":\"e1\",\"x\":1}", Attachment.class));
    assertFalse(a.equals("e1"));
    assertEquals("Email{\"id\":\"e1\",\"x\":1}", a.toString());
    assertNull(decode("{}", Email.class).getEvents());
    assertNull(decode("{}", Email.class).getMetadata());
    assertEquals(Map.of("k", "v"), decode("{\"metadata\":{\"k\":\"v\"}}", Email.class).getMetadata());
  }

  @Test
  void parametersAreValues() {
    Tag a = Tag.builder().name("n").value("v").build();
    assertEquals(a, Tag.builder().name("n").value("v").build());
    assertNotEquals(a, Tag.builder().name("n").value("w").build());
    assertNotEquals(a, OutgoingAttachment.builder().filename("n").build());
    assertFalse(a.equals(null));
    assertEquals("Tag{name=n, value=v}", a.toString());
    assertEquals(Map.of("name", "n", "value", "v"), a.toMap());
    TemplatesCreateParams p = TemplatesCreateParams.builder().name("n").variables(null).variableSchema((List<VariableSpec>) null)
        .variableSchema((VariableSpec[]) null).build();
    assertNull(p.getVariables());
    assertTrue(p.toMap().containsKey("variable_schema"));
    TemplatesCreateParams q = TemplatesCreateParams.builder().name("n").variables(Map.of("a", 1))
        .variableSchema(VariableSpec.builder().name("v").type(VariableSpec.Type.STRING).build()).build();
    assertEquals(1, q.getVariables().get("a"));
    assertEquals("string", q.getVariableSchema().get(0).getType());
    JsonNode tree = Json.MAPPER.valueToTree(q);
    assertEquals("v", tree.path("variable_schema").get(0).path("name").asText());
    assertEquals(Arrays.asList("a", null), com.zapsmtp.params.SendEmailParams.builder().to("a", null).build().getTo());
  }
}
