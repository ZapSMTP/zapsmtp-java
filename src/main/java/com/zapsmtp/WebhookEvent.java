package com.zapsmtp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zapsmtp.core.ApiModel;
import com.zapsmtp.core.Json;
import java.time.OffsetDateTime;
import java.util.Map;

/** A webhook request's body, returned by {@link Webhooks#verify(String, String, String)}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class WebhookEvent extends ApiModel {
  @JsonProperty("id")
  private String id;

  @JsonProperty("type")
  private String type;

  @JsonProperty("created_at")
  private OffsetDateTime createdAt;

  @JsonProperty("data")
  private Map<String, Object> data;

  private WebhookEvent() {}

  /**
   * The event id, evt_...
   *
   * @return the id, or null on email.received, thread.created and thread.replied (see the Zap-Event-Id header)
   */
  @JsonProperty("id")
  public String getId() {
    return id;
  }

  /**
   * The event type, for example {@code email.delivered}.
   *
   * @return the type
   */
  @JsonProperty("type")
  public String getType() {
    return type;
  }

  /**
   * When the event happened.
   *
   * @return the time, or null
   */
  @JsonProperty("created_at")
  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  /**
   * The event's data as JSON values (maps, lists, strings, numbers, booleans).
   *
   * @return an unmodifiable map, or null
   */
  @JsonProperty("data")
  public Map<String, Object> getData() {
    return view(data);
  }

  /**
   * The event's data decoded into a type, such as one of the SDK's models or a class of your own.
   *
   * @param type the class
   * @param <T> the type
   * @return the data, or null when there is none
   */
  public <T> T getData(Class<T> type) {
    return Json.decode(Json.MAPPER.valueToTree(data), Json.MAPPER.constructType(type));
  }
}
