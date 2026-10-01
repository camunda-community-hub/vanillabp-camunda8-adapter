package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * When the reading of a search stops, and what it says about where it stopped.
 * <p>
 * The three callers prove that every page is read. What is left over for here are the ends
 * of the paging, because each of them means something different to a caller: a page the
 * cluster could not fill is the end of the set, a bound reached is an answer which is only
 * a part, and a full page without a cursor to go on from is the second kind as well.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SearchPagesTest {

  /**
   * The cursors the pages were asked for, <code>null</code> for the first one.
   */
  private final List<String> cursorsAskedFor = new ArrayList<>();

  @Test
  @DisplayName("A page the cluster could not fill ends the reading, and the answer is the whole set")
  public void aPartialPageEndsTheReading() {

    final var read = Camunda8SearchPages.everyPage(cursor -> {
      cursorsAskedFor.add(cursor);
      return page(entries(7), "cursor-of-the-short-page");
    });

    assertEquals(7, read.items().size());
    assertFalse(read.theClusterHadMore(), "there is no second page to ask for");
    assertEquals(1, cursorsAskedFor.size(), () -> "one search, but was "
        + cursorsAskedFor);

  }

  @Test
  @DisplayName("Reading stops at the bound and says that the cluster still had more")
  public void theBoundIsReachedAndReported() {

    final var read = Camunda8SearchPages.everyPage(3, cursor -> {
      cursorsAskedFor.add(cursor);
      return page(entries(Camunda8SearchPages.PAGE_SIZE), "cursor-"
          + cursorsAskedFor.size());
    });

    assertEquals(3 * Camunda8SearchPages.PAGE_SIZE, read.items().size(), "three pages and no more");
    assertTrue(
        read.theClusterHadMore(),
        "the caller has to know that its answer is a part, because nothing else in it says so");
    assertEquals(
        List.of("cursor-1", "cursor-2"),
        cursorsAskedFor.subList(1, cursorsAskedFor.size()),
        "every page after the first one is asked for from where the one before ended");

  }

  @Test
  @DisplayName("A full page without a cursor is as far as the reading gets")
  public void aFullPageWithoutACursorEndsTheReading() {

    final var read = Camunda8SearchPages.everyPage(cursor -> {
      cursorsAskedFor.add(cursor);
      return page(entries(Camunda8SearchPages.PAGE_SIZE), " ");
    });

    assertEquals(Camunda8SearchPages.PAGE_SIZE, read.items().size());
    assertTrue(read.theClusterHadMore(), "a set which cannot be read on is not a set which ended");

  }

  @Test
  @DisplayName("A cluster holding nothing is one search and an empty answer")
  public void anEmptyAnswerIsOneSearch() {

    final var read = Camunda8SearchPages.everyPage(cursor -> {
      cursorsAskedFor.add(cursor);
      return page(List.of(), null);
    });

    assertEquals(List.of(), read.items());
    assertFalse(read.theClusterHadMore());
    assertEquals(1, cursorsAskedFor.size());

  }

  @Test
  @DisplayName("A caller looking for one entry stops on the page that entry is on")
  public void theSearchForOneEntryStopsOnItsPage() {

    final var read = Camunda8SearchPages
        .pagesUntil("entry 150"::equals, Camunda8SearchPages.MAX_PAGES, cursor -> {
          cursorsAskedFor.add(cursor);
          final var from = (cursorsAskedFor.size() - 1) * Camunda8SearchPages.PAGE_SIZE;
          return page(
              IntStream
                  .range(from, from + Camunda8SearchPages.PAGE_SIZE)
                  .mapToObj(entry -> "entry "
                      + entry)
                  .toList(),
              "cursor-"
                  + cursorsAskedFor.size());
        });

    assertEquals(2, cursorsAskedFor.size(), "the second page holds it, and the third is not asked for");
    assertTrue(read.items().contains("entry 150"));
    assertFalse(read.theClusterHadMore(), "what the caller was looking for was found, nothing is missing");

  }

  private static List<String> entries(
      final int howMany) {

    return IntStream
        .range(0, howMany)
        .mapToObj(entry -> "entry "
            + entry)
        .toList();

  }

  private static <T> SearchResponse<T> page(
      final List<T> items,
      final String endCursor) {

    @SuppressWarnings("unchecked")
    final SearchResponse<T> response = mock(SearchResponse.class);
    final var page = mock(SearchResponsePage.class);
    Mockito.lenient().when(page.endCursor()).thenReturn(endCursor);
    Mockito.lenient().when(response.items()).thenReturn(items);
    Mockito.lenient().when(response.page()).thenReturn(page);
    return response;

  }

}
