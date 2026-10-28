package com.zapsmtp.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.zapsmtp.RequestOptions;
import com.zapsmtp.ZapResponse;
import com.zapsmtp.ZapSMTP;
import com.zapsmtp.core.ApiParams;
import com.zapsmtp.core.Json;
import com.zapsmtp.params.SendEmailParams;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The shared test data in sdks/: conformance cases and webhook vectors, and calling an operation by reflection. */
public final class Cases {
  private Cases() {}

  public static Path sdks() {
    return Path.of(System.getProperty("zapsmtp.sdks", ".."));
  }

  public static JsonNode read(String file) {
    try {
      return Json.MAPPER.readTree(Files.readAllBytes(sdks().resolve(file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static List<JsonNode> cases() {
    List<JsonNode> out = new ArrayList<>();
    read("conformance/cases.json").forEach(out::add);
    return out;
  }

  /** The service object a case's call.resource names, e.g. zap.emails().receiving(). */
  public static Object service(ZapSMTP zap, JsonNode c) throws ReflectiveOperationException {
    Object target = zap;
    for (JsonNode r : c.path("call").path("resource")) {
      target = target.getClass().getMethod(r.asText()).invoke(target);
    }
    return target;
  }

  /** The public methods named after the case's operation, with and without "WithResponse". */
  public static List<Method> overloads(Object service, JsonNode c) {
    String name = c.path("call").path("method").asText();
    List<Method> out = new ArrayList<>();
    for (Method m : service.getClass().getMethods()) {
      if (m.getName().equals(name) || m.getName().equals(name + "WithResponse")) {
        out.add(m);
      }
    }
    return out;
  }

  /** The arguments for a method from a case: path args, typed params, emails, batch options, request options. */
  public static Object[] args(Method m, JsonNode c, RequestOptions options) {
    JsonNode call = c.path("call");
    List<Object> args = new ArrayList<>();
    int path = 0;
    for (java.lang.reflect.Parameter p : m.getParameters()) {
      Class<?> t = p.getType();
      if (t == String.class) {
        args.add(call.path("pathArgs").get(path++).asText());
      } else if (t == RequestOptions.class) {
        args.add(options);
      } else if (t == List.class) {
        Class<?> item = (Class<?>) ((ParameterizedType) p.getParameterizedType()).getActualTypeArguments()[0];
        List<Object> items = new ArrayList<>();
        call.path("items").forEach((i) -> items.add(Json.MAPPER.convertValue(i, item)));
        if (item != SendEmailParams.class) {
          throw new AssertionError("unexpected list of " + item);
        }
        args.add(items);
      } else if (ApiParams.class.isAssignableFrom(t)) {
        JsonNode json = call.has("items") ? call.path("options") : call.path("params");
        args.add(Json.MAPPER.convertValue(json, t));
      } else {
        throw new AssertionError("unexpected parameter " + t);
      }
    }
    return args.toArray();
  }

  /** Whether a method takes every argument the case has (the overloads without optional parameters do not). */
  public static boolean takesAll(Method m, JsonNode c) {
    JsonNode call = c.path("call");
    int strings = 0;
    boolean params = false;
    for (Class<?> t : m.getParameterTypes()) {
      strings += t == String.class ? 1 : 0;
      params |= ApiParams.class.isAssignableFrom(t);
    }
    boolean wantsParams = !call.path("params").isNull() || call.has("items");
    return strings == call.path("pathArgs").size() && params == wantsParams;
  }

  /** Calls a method; the data of a ZapResponse is returned with the response. */
  public static Object[] invoke(Object service, Method m, Object[] args) throws Throwable {
    try {
      Object r = m.invoke(service, args);
      return r instanceof ZapResponse ? new Object[] {((ZapResponse<?>) r).getData(), r} : new Object[] {r, null};
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
