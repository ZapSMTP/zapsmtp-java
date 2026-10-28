package com.zapsmtp.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a list asks for its next page (sdks/spec/names.json paging). The caller's other parameters are kept.
 *
 * <p>Internal: not part of the SDK's public API.
 */
public enum Paging {
  /** {@code before} = the last item's id. */
  BEFORE {
    @Override
    Map<String, Object> next(Map<String, Object> query, JsonNode data, String cursor) {
      String last = text(data.get(data.size() - 1), "id");
      return last == null ? null : Map.of("before", last);
    }
  },
  /** {@code after} = the last item's id. */
  AFTER {
    @Override
    Map<String, Object> next(Map<String, Object> query, JsonNode data, String cursor) {
      String last = text(data.get(data.size() - 1), "id");
      return last == null ? null : Map.of("after", last);
    }
  },
  /**
   * Logs: back with {@code before} = the last id; when the caller paged forward with {@code after}, on with
   * {@code after} = the first (newest) id.
   */
  LOGS {
    @Override
    Map<String, Object> next(Map<String, Object> query, JsonNode data, String cursor) {
      if (query.containsKey("after")) {
        String first = text(data.get(0), "id");
        return first == null ? null : Map.of("after", first);
      }
      return BEFORE.next(query, data, cursor);
    }
  },
  /** Threads: {@code before} = the last thread's last_message_at, {@code before_id} = its id. */
  THREAD {
    @Override
    Map<String, Object> next(Map<String, Object> query, JsonNode data, String cursor) {
      JsonNode last = data.get(data.size() - 1);
      String id = text(last, "id");
      String at = text(last, "last_message_at");
      if (id == null || at == null) {
        return null;
      }
      Map<String, Object> next = new LinkedHashMap<>();
      next.put("before", at);
      next.put("before_id", id);
      return next;
    }
  },
  /** {@code after} = the page's next_cursor. */
  CURSOR {
    @Override
    Map<String, Object> next(Map<String, Object> query, JsonNode data, String cursor) {
      return cursor == null ? null : Map.of("after", cursor);
    }
  },
  /** One page only. */
  NONE {
    @Override
    Map<String, Object> next(Map<String, Object> query, JsonNode data, String cursor) {
      return null;
    }
  };

  /**
   * The query parameters that ask for the next page.
   *
   * @param query the query of the page's request
   * @param data the page's items, a JSON array
   * @param cursor the page's next_cursor, or null
   * @return the parameters to add, or null when no next page can be asked for
   */
  abstract Map<String, Object> next(Map<String, Object> query, JsonNode data, String cursor);

  /** A non-empty string field of an item, else null. */
  static String text(JsonNode item, String field) {
    JsonNode v = item == null ? null : item.get(field);
    return v != null && v.isTextual() && !v.textValue().isEmpty() ? v.textValue() : null;
  }
}
