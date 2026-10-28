package com.zapsmtp.core;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The base of every request type: an immutable, ordered set of the parameters that were set. A parameter set to
 * null is sent as null (to clear a value); one never set is not sent.
 */
public abstract class ApiParams {
  private final Map<String, Object> values;

  /**
   * Copies a builder's values.
   *
   * @param builder the builder
   */
  protected ApiParams(Builder<?> builder) {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(builder.values));
  }

  /**
   * The parameters that were set, by their API names, as sent.
   *
   * @return an unmodifiable map
   */
  @JsonValue
  public final Map<String, Object> toMap() {
    return values;
  }

  /**
   * A parameter's value.
   *
   * @param name its API name
   * @param <T> its type
   * @return the value, or null when not set
   */
  @SuppressWarnings("unchecked")
  protected final <T> T get(String name) {
    return (T) values.get(name);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ApiParams && o.getClass() == getClass() && values.equals(((ApiParams) o).values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + values;
  }

  /**
   * The base of the request types' builders.
   *
   * @param <B> the builder's own type
   */
  public abstract static class Builder<B extends Builder<B>> {
    private final Map<String, Object> values = new LinkedHashMap<>();

    /** Starts empty. */
    protected Builder() {}

    /**
     * Starts with the values of a request.
     *
     * @param from the request to copy
     */
    protected Builder(ApiParams from) {
      values.putAll(from.values);
    }

    /**
     * Sets a parameter.
     *
     * @param name its API name
     * @param value the value; null sends null
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    protected final B put(String name, Object value) {
      values.put(name, value);
      return (B) this;
    }

    /**
     * Fails unless the parameters are set and not null.
     *
     * @param names their API names
     * @throws IllegalStateException naming the first one missing
     */
    protected final void require(String... names) {
      for (String n : names) {
        if (values.get(n) == null) {
          throw new IllegalStateException(n + " is required");
        }
      }
    }

    /**
     * A deep, unmodifiable copy of a JSON value: nested maps and lists are copied too.
     *
     * @param value the value, or null
     * @return the copy
     */
    protected static Object copy(Object value) {
      return Frozen.copy(value);
    }

    /**
     * A deep, unmodifiable copy of an array.
     *
     * @param items the items, or null
     * @param <T> the item type
     * @return the list, or null
     */
    @SafeVarargs
    protected static <T> List<T> list(T... items) {
      if (items == null) {
        return null;
      }
      List<T> out = new ArrayList<>(items.length);
      for (T item : items) {
        out.add(item);
      }
      return Frozen.list(out);
    }

    /**
     * A deep, unmodifiable copy of a collection.
     *
     * @param items the items, or null
     * @param <T> the item type
     * @return the list, or null
     */
    protected static <T> List<T> list(Collection<? extends T> items) {
      return Frozen.list(items);
    }

    /**
     * A deep, unmodifiable copy of a map.
     *
     * @param map the map, or null
     * @param <V> the value type
     * @return the copy, or null
     */
    protected static <V> Map<String, V> map(Map<String, ? extends V> map) {
      return Frozen.map(map);
    }
  }
}
