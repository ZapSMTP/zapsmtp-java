package com.zapsmtp.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The base of every response model. Models are immutable; a getter returns null when the API left its field out.
 * Fields this SDK version does not know are kept in {@link #getAdditionalProperties()}.
 */
public abstract class ApiModel {
  private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

  /** For the subclasses. */
  protected ApiModel() {}

  /**
   * The fields of the response this SDK version has no getter for.
   *
   * @return an unmodifiable map from the API's field names to their JSON values (unmodifiable maps and lists,
   *     strings, numbers, booleans, nulls)
   */
  @JsonAnyGetter
  public final Map<String, Object> getAdditionalProperties() {
    return Collections.unmodifiableMap(additionalProperties);
  }

  @JsonAnySetter
  final void putAdditionalProperty(String name, Object value) {
    additionalProperties.put(name, Frozen.copy(value));
  }

  /**
   * A deep, unmodifiable copy of a list field.
   *
   * @param list the list, or null
   * @param <T> the item type
   * @return the copy, or null
   */
  protected static <T> List<T> view(List<T> list) {
    return Frozen.list(list);
  }

  /**
   * A deep, unmodifiable copy of a map field.
   *
   * @param map the map, or null
   * @param <V> the value type
   * @return the copy, or null
   */
  protected static <V> Map<String, V> view(Map<String, V> map) {
    return Frozen.map(map);
  }

  /**
   * A deep, unmodifiable copy of a free-form JSON field.
   *
   * @param value the value, or null
   * @return the copy
   */
  protected static Object view(Object value) {
    return Frozen.copy(value);
  }

  /** Two models are equal when they are of the same class and hold the same JSON. */
  @Override
  public boolean equals(Object o) {
    return o instanceof ApiModel && o.getClass() == getClass()
        && Json.MAPPER.valueToTree(this).equals(Json.MAPPER.valueToTree(o));
  }

  @Override
  public int hashCode() {
    return Json.MAPPER.valueToTree(this).hashCode();
  }

  /** The class name and the model as JSON. */
  @Override
  public String toString() {
    return getClass().getSimpleName() + Json.MAPPER.valueToTree(this);
  }
}
