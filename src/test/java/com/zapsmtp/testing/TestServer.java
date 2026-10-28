package com.zapsmtp.testing;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** A local HTTP server that answers from a queue (the last answer repeats) and records every request. */
public final class TestServer implements AutoCloseable {
  /** One recorded request. */
  public static final class Seen {
    public final String method;
    public final String path;
    public final String query;
    public final Headers headers;
    public final byte[] body;

    Seen(HttpExchange ex, byte[] body) {
      method = ex.getRequestMethod();
      path = ex.getRequestURI().getRawPath();
      query = ex.getRequestURI().getRawQuery();
      headers = ex.getRequestHeaders();
      this.body = body;
    }

    public String header(String name) {
      return headers.getFirst(name);
    }

    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    /** The path and query, as sent. */
    public String target() {
      return query == null ? path : path + "?" + query;
    }
  }

  /** One answer. */
  public static final class Answer {
    int status = 200;
    final Map<String, String> headers = new LinkedHashMap<>();
    byte[] body = new byte[0];
    long delayMs;
    boolean stall;
    boolean broken;

    public Answer header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    public Answer delay(long ms) {
      delayMs = ms;
      return this;
    }
  }

  public static Answer json(int status, String body) {
    Answer a = new Answer();
    a.status = status;
    a.body = body.getBytes(StandardCharsets.UTF_8);
    a.headers.put("Content-Type", "application/json");
    a.headers.put("X-Request-Id", "req_1");
    return a;
  }

  public static Answer text(int status, String body) {
    Answer a = new Answer();
    a.status = status;
    a.body = body.getBytes(StandardCharsets.UTF_8);
    return a;
  }

  public static Answer apiError(int status, String code) {
    return json(status, "{\"statusCode\":" + status + ",\"name\":\"" + code + "\",\"message\":\"" + code + " happened\","
        + "\"error\":{\"code\":\"" + code + "\",\"message\":\"" + code + " happened\",\"type\":\"invalid_request_error\"}}");
  }

  /** Headers, then "{" and nothing more. */
  public static Answer stall() {
    Answer a = new Answer();
    a.stall = true;
    return a;
  }

  /** Headers announcing 100 bytes, then 1 byte and a closed connection. */
  public static Answer broken() {
    Answer a = new Answer();
    a.broken = true;
    a.headers.put("X-Request-Id", "req_broken");
    return a;
  }

  private final HttpServer server;
  private final ExecutorService pool = Executors.newCachedThreadPool();
  private final Deque<Answer> answers = new ArrayDeque<>();
  private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
  private Function<Seen, Answer> responder;

  public TestServer(Answer... answers) throws IOException {
    Collections.addAll(this.answers, answers);
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(pool);
    server.createContext("/", this::handle);
    server.start();
  }

  /** Answers every request with a function of it instead of the queue. */
  public TestServer respond(Function<Seen, Answer> f) {
    responder = f;
    return this;
  }

  public void enqueue(Answer... more) {
    synchronized (answers) {
      Collections.addAll(answers, more);
    }
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  public List<Seen> seen() {
    synchronized (seen) {
      return new ArrayList<>(seen);
    }
  }

  public Seen last() {
    List<Seen> all = seen();
    return all.get(all.size() - 1);
  }

  public int count() {
    return seen.size();
  }

  private void handle(HttpExchange ex) throws IOException {
    byte[] body = ex.getRequestBody().readAllBytes();
    Seen s = new Seen(ex, body);
    seen.add(s);
    Answer a;
    if (responder != null) {
      a = responder.apply(s);
    } else {
      synchronized (answers) {
        a = answers.size() > 1 ? answers.poll() : answers.peek();
      }
    }
    try {
      if (a.delayMs > 0) {
        java.lang.Thread.sleep(a.delayMs);
      }
      a.headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
      if (a.stall) {
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        out.write('{');
        out.flush();
        java.lang.Thread.sleep(10_000);
      } else if (a.broken) {
        ex.sendResponseHeaders(200, 100);
        OutputStream out = ex.getResponseBody();
        out.write('{');
        out.flush();
        ex.close();
      } else {
        ex.sendResponseHeaders(a.status, a.body.length == 0 ? -1 : a.body.length);
        try (OutputStream out = ex.getResponseBody()) {
          out.write(a.body);
        }
      }
    } catch (InterruptedException e) {
      java.lang.Thread.currentThread().interrupt();
    } catch (IOException e) {
      // the client went away
    } finally {
      ex.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
    pool.shutdownNow();
  }
}
