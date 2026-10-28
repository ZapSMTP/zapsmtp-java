package com.zapsmtp.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;

/**
 * The SDK's JSON mapper. Decoding never fails on a surprise: unknown fields are kept in
 * {@link ApiModel#getAdditionalProperties()}, a single value is taken as a one-item list, and a value of the wrong
 * type (a fractional or too large number for an integer too) becomes null instead of an error.
 *
 * <p>Internal: not part of the SDK's public API.
 */
public final class Json {
  /** The configured mapper. */
  public static final ObjectMapper MAPPER = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
      .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
      .addHandler(new Lenient())
      .addModule(new SimpleModule().addDeserializer(Long.class, new LenientLong())
          .addDeserializer(Double.class, new FiniteDouble()))
      // a fractional number for an integer field is the wrong type: null, never truncated
      .withCoercionConfig(LogicalType.Integer, (c) -> c.setCoercion(CoercionInputShape.Float, CoercionAction.AsNull))
      .build();

  private Json() {}

  /**
   * Parses a response body.
   *
   * @param text the body
   * @return the JSON, a text node when it is not JSON, or null when it is empty
   */
  public static JsonNode parse(String text) {
    if (text.isBlank()) {
      return null;
    }
    try {
      return MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      return TextNode.valueOf(text);
    }
  }

  /**
   * Decodes JSON into a type, leniently.
   *
   * @param node the JSON, or null
   * @param type the type
   * @param <T> the type
   * @return the value, or null when there is no JSON or it cannot be decoded at all
   */
  public static <T> T decode(JsonNode node, JavaType type) {
    if (node == null) {
      return null;
    }
    try {
      return MAPPER.convertValue(node, type);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Turns JSON into plain Java values: maps, lists, strings, numbers, booleans.
   *
   * @param node the JSON, or null
   * @return the value, or null
   */
  public static Object toJava(JsonNode node) {
    return node == null ? null : MAPPER.convertValue(node, Object.class);
  }

  /**
   * Writes a value as JSON.
   *
   * @param value the value
   * @return UTF-8 JSON
   * @throws IllegalArgumentException when the value cannot be written as JSON
   */
  public static byte[] write(Object value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot write the request body as JSON: " + e.getOriginalMessage(), e);
    }
  }

  /** A whole number too large for a long is null instead of failing the whole response. */
  static final class LenientLong extends StdDeserializer<Long> {
    private static final long serialVersionUID = 1L;
    private static final NumberDeserializers.LongDeserializer STANDARD =
        new NumberDeserializers.LongDeserializer(Long.class, null);

    LenientLong() {
      super(Long.class);
    }

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (p.hasToken(JsonToken.VALUE_NUMBER_INT) && p.getNumberType() == JsonParser.NumberType.BIG_INTEGER) {
        return null;
      }
      return STANDARD.deserialize(p, ctxt);
    }
  }

  /** NaN and infinity (1e999, "NaN", "Infinity") are no JSON numbers: null. */
  static final class FiniteDouble extends StdDeserializer<Double> {
    private static final long serialVersionUID = 1L;
    private static final NumberDeserializers.DoubleDeserializer STANDARD =
        new NumberDeserializers.DoubleDeserializer(Double.class, null);

    FiniteDouble() {
      super(Double.class);
    }

    @Override
    public Double deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      Double d = STANDARD.deserialize(p, ctxt);
      return d == null || Double.isFinite(d) ? d : null;
    }
  }

  /** Turns values of the wrong type into null instead of failing the whole response. */
  static final class Lenient extends DeserializationProblemHandler {
    @Override
    public Object handleUnexpectedToken(DeserializationContext ctxt, JavaType targetType, JsonToken t, JsonParser p,
        String failureMsg) throws IOException {
      p.skipChildren();
      return null;
    }

    @Override
    public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType, String valueToConvert,
        String failureMsg) {
      return null;
    }

    @Override
    public Object handleMissingInstantiator(DeserializationContext ctxt, Class<?> instClass,
        ValueInstantiator valueInsts, JsonParser p, String msg) throws IOException {
      p.skipChildren();
      return null;
    }

    @Override
    public Object handleInstantiationProblem(DeserializationContext ctxt, Class<?> instClass, Object argument,
        Throwable t) {
      return null;
    }
  }
}
