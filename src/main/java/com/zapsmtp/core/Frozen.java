package com.zapsmtp.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep, unmodifiable copies of JSON-like values: maps and collections at every level are copied and frozen, so
 * neither the caller's objects nor a returned value can change an SDK object afterwards.
 *
 * <p>Internal: not part of the SDK's public API.
 */
public final class Frozen {
  private Frozen() {}

  /**
   * A deep, unmodifiable copy.
   *
   * @param value a map, a collection (copied as a list), any other value (kept as is, except that a NaN or
   *     infinite double becomes null), or null
   * @return the copy
   */
  public static Object copy(Object value) {
    if (value instanceof Map) {
      Map<Object, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        out.put(e.getKey(), copy(e.getValue()));
      }
      return Collections.unmodifiableMap(out);
    }
    if (value instanceof Collection) {
      List<Object> out = new ArrayList<>();
      for (Object item : (Collection<?>) value) {
        out.add(copy(item));
      }
      return Collections.unmodifiableList(out);
    }
    if (value instanceof Double && !Double.isFinite((Double) value)) {
      return null; // JSON has no NaN or infinity
    }
    return value;
  }

  /**
   * A deep, unmodifiable copy of a collection.
   *
   * @param items the items, or null
   * @param <T> the item type
   * @return the list, or null
   */
  @SuppressWarnings("unchecked")
  public static <T> List<T> list(Collection<? extends T> items) {
    return (List<T>) copy(items);
  }

  /**
   * A deep, unmodifiable copy of a map.
   *
   * @param map the map, or null
   * @param <V> the value type
   * @return the copy, or null
   */
  @SuppressWarnings("unchecked")
  public static <V> Map<String, V> map(Map<String, ? extends V> map) {
    return (Map<String, V>) copy(map);
  }
}
