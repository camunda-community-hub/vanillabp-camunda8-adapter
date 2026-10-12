package io.vanillabp.camunda8.processservice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import io.camunda.client.api.search.response.SearchResponse;

/**
 * Reads a search of the cluster of this adapter page by page. It is for every search whose
 * answer is a SET and not a page.
 * <p>
 * A search which says nothing about the page it wants gets one page, and that page holds
 * 100 entries. Measured on 2026-10-01 against {@code camunda/camunda:8.10.0}: a cluster
 * which held 110 versions of one process answered a search without a page with 100 of
 * them, oldest first. So the ten newest were missing. The same set, read newest first with
 * an explicit limit and the cursor of the page before, came back complete. It took two
 * pages and 66 milliseconds. That is what it costs to get past the limit.
 * <p>
 * Nothing fails at that limit. The caller gets a page which looks like the whole answer.
 * So whoever reads it reports the oldest 100 of something, and nobody is told that there
 * were more. That is why the paging lives here and not at each caller. Three searches hit
 * it: a startup check, a workflow history, and the scope a task runs in. Each of them was
 * wrong in its own quiet way.
 * <p>
 * Every caller asks for {@link #PAGE_SIZE} entries per page. Only a full page tells this
 * class that there may be a next one. A search where ONE page is the whole answer does not
 * come through here at all. It names its limit at the call site, with a sentence which says
 * why that number is enough.
 * <p>
 * It is public, because an extension which searches the same cluster meets the same limit.
 */
public final class Camunda8SearchPages {

  /**
   * How many entries one page asks for. It is the default of the cluster for a search which
   * names no page. It is kept, because a page is a request over the network, and a bigger
   * page is a bigger answer to hold in memory.
   */
  public static final int PAGE_SIZE = 100;

  /**
   * Where the paging stops, whatever the cluster still offers. That is 10000 entries. Each
   * page is a request. A caller which reads a set this large would be better off with a
   * narrower question. And a reader which never stops is worse than one which says where
   * it stopped.
   */
  public static final int MAX_PAGES = 100;

  private Camunda8SearchPages() {
    // static helper
  }

  /**
   * What the pages of one search held.
   *
   * @param <T> What the search answers with
   * @param items The entries of every page which was read
   * @param theClusterHadMore Whether the reading stopped at its own page limit while the
   *          cluster still offered a next page. Then the entries are a part and not the
   *          whole. A caller which cannot pass that on says it in its log. An incomplete
   *          answer which looks complete is the bug this class is there to prevent
   */
  public record PagesRead<T>(
                             List<T> items,
                             boolean theClusterHadMore) {
  }

  /**
   * Every entry of a search, page by page.
   *
   * @param <T> What the search answers with
   * @param onePage Runs the search from the given cursor, <code>null</code> for the first
   *          page, asking for {@link #PAGE_SIZE} entries
   * @return The entries, and whether the paging stopped at its bound
   */
  public static <T> PagesRead<T> everyPage(
      final Function<String, SearchResponse<T>> onePage) {

    return everyPage(MAX_PAGES, onePage);

  }

  /**
   * Every entry of a search, page by page, with a bound of the caller's own.
   *
   * @param <T> What the search answers with
   * @param maxPages How many pages this search may cost. A caller lowers it if a quick
   *          answer matters more than knowing everything
   * @param onePage Runs the search from the given cursor, <code>null</code> for the first
   *          page, asking for {@link #PAGE_SIZE} entries
   * @return The entries, and whether the paging stopped at its bound
   */
  public static <T> PagesRead<T> everyPage(
      final int maxPages,
      final Function<String, SearchResponse<T>> onePage) {

    return pagesUntil(entry -> false, maxPages, onePage);

  }

  /**
   * Pages of a search up to and including the page holding what the caller was looking
   * for.
   * <p>
   * For a caller which searches a set for ONE entry. It reads only the pages it needs, not
   * the whole set. That matters if the set is the iterations of a multi-instance, and the
   * search runs while a workflow is being served.
   *
   * @param <T> What the search answers with
   * @param found What the caller was looking for, tested against every entry which comes
   *          back
   * @param maxPages How many pages this search may cost
   * @param onePage Runs the search from the given cursor, <code>null</code> for the first
   *          page, asking for {@link #PAGE_SIZE} entries
   * @return The entries up to and including the page the match was on, and whether the
   *         paging stopped at its bound
   */
  public static <T> PagesRead<T> pagesUntil(
      final Predicate<T> found,
      final int maxPages,
      final Function<String, SearchResponse<T>> onePage) {

    final var items = new ArrayList<T>();
    String cursor = null;
    for (var pages = 0; pages < maxPages; pages++) {
      final var answer = onePage.apply(cursor);
      final var fetched = answer.items();
      if ((fetched == null) || fetched.isEmpty()) {
        return new PagesRead<>(items, false);
      }
      items.addAll(fetched);
      if (fetched.stream().anyMatch(found)) {
        return new PagesRead<>(items, false);
      }
      if (fetched.size() < PAGE_SIZE) {
        // a page the cluster could not fill is the last one it holds
        return new PagesRead<>(items, false);
      }
      cursor = answer
          .page()
          .endCursor();
      if ((cursor == null) || cursor.isBlank()) {
        // a full page without a cursor to go on from. There is no way to ask further,
        // and to claim that the set ends here would be a guess
        return new PagesRead<>(items, true);
      }
    }
    return new PagesRead<>(items, true);

  }

}
