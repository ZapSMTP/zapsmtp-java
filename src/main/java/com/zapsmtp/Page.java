package com.zapsmtp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * One page of a list. {@link #autoPagingIterable()} and {@link #stream()} walk every item of this page and the
 * pages after it, asking for each next page when it is reached.
 *
 * <pre>{@code
 * for (Email email : zap.emails().list().autoPagingIterable()) {
 *   System.out.println(email.getId());
 * }
 * }</pre>
 *
 * <p>As JSON (Jackson) a page is what the API sent: {@code data}, {@code has_more} and {@code next_cursor}.
 * Writing it never loads another page.
 *
 * @param <T> the item type
 */
@JsonPropertyOrder({"data", "has_more", "next_cursor"})
public final class Page<T> {
  private final List<T> data;
  private final boolean hasMore;
  private final String nextCursor;
  private final Supplier<Page<T>> next;

  private Page(List<T> data, boolean hasMore, String nextCursor, Supplier<Page<T>> next) {
    this.data = Collections.unmodifiableList(new ArrayList<>(data));
    this.hasMore = hasMore;
    this.nextCursor = nextCursor;
    this.next = next;
  }

  /**
   * Creates a page, for example to stub the SDK in tests.
   *
   * @param data the items
   * @param hasMore whether the API has more items
   * @param nextCursor the API's next_cursor, or null
   * @param next loads the next page, or null when there is none to ask for
   * @param <T> the item type
   * @return the page
   */
  public static <T> Page<T> of(List<T> data, boolean hasMore, String nextCursor, Supplier<Page<T>> next) {
    return new Page<>(data, hasMore, nextCursor, next);
  }

  /**
   * The items of this page.
   *
   * @return an unmodifiable list
   */
  @JsonProperty("data")
  public List<T> getData() {
    return data;
  }

  /**
   * Whether the API said more items follow ({@code has_more}).
   *
   * @return true when more follow
   */
  @JsonProperty("has_more")
  public boolean hasMore() {
    return hasMore;
  }

  /**
   * The API's {@code next_cursor} (suppressions).
   *
   * @return the cursor, or null
   */
  @JsonProperty("next_cursor")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String getNextCursor() {
    return nextCursor;
  }

  /**
   * Whether there is a next page to ask for. No request is made.
   *
   * @return true when {@link #getNextPage()} has a page to load
   */
  @JsonIgnore
  public boolean hasNextPage() {
    return next != null;
  }

  /**
   * Loads the next page.
   *
   * @return the next page, or null after the last one
   * @throws com.zapsmtp.errors.ZapSMTPException when the request fails
   */
  @JsonIgnore // a request: never a property (serializing a page would walk every page after it)
  public Page<T> getNextPage() {
    return next == null ? null : next.get();
  }

  /**
   * Every item of this page and the pages after it.
   *
   * @return an iterable that loads the next pages as it reaches them
   */
  public Iterable<T> autoPagingIterable() {
    return () -> new Iterator<T>() {
      private Page<T> page = Page.this;
      private int index;

      @Override
      public boolean hasNext() {
        while (index >= page.data.size()) {
          Page<T> following = page.getNextPage();
          if (following == null) {
            return false;
          }
          page = following;
          index = 0;
        }
        return true;
      }

      @Override
      public T next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return page.data.get(index++);
      }
    };
  }

  /**
   * Every item of this page and the pages after it, as a sequential stream.
   *
   * @return the stream
   */
  public Stream<T> stream() {
    return StreamSupport.stream(autoPagingIterable().spliterator(), false);
  }
}
