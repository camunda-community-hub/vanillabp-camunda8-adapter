package io.vanillabp.camunda8.processservice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import io.camunda.client.api.search.response.SearchResponse;

/**
 * Reads a search of this adapter's cluster page by page, for every search whose answer is
 * a SET and not a page.
 * <p>
 * A search which says nothing about the page it wants is answered with one page, and that
 * page holds 100 entries. Measured on 2026-10-01 against {@code camunda/camunda:8.10.0}: a
 * cluster holding 110 versions of one process answered a search naming no page with 100 of
 * them, oldest first, so the ten newest were missing. The same set read newest first with an
 * explicit limit and the cursor of the page before came back complete, in two pages and 66
 * milliseconds, which is what the ceiling costs to get past.
 * <p>
 * Nothing about that ceiling fails. The caller is handed a page which looks like the whole
 * answer, so whoever reads it reports the oldest 100 of something and nobody is told that
 * there were more. That is why the paging lives here instead of at each caller: the three
 * searches which hit it answered a startup check, a workflow history and the scope a task
 * runs in, and each of them was wrong in its own quiet way.
 * <p>
 * Every caller asks for {@link #PAGE_SIZE} entries per page, because a full page is what
 * tells this class that there may be a next one. A search where ONE page is the whole
 * answer does not come through here at all: it names its limit at the call site, with a
 * sentence saying why that number is enough.
 * <p>
 * Public because an extension searching the same cluster meets the same ceiling.
 */
public final class Camunda8SearchPages {

  /**
   * How many entries one page asks for. The cluster's own default for a search which names
   * no page, kept because a page is a request over the wire and a bigger one is a bigger
   * answer to hold in memory.
   */
  public static final int PAGE_SIZE = 100;

  /**
   * Where the paging stops regardless of what the cluster still offers, which is 10000
   * entries. A search is a request each, so a set this large is read by a caller which
   * would be better off asking a narrower question - and a reader which never stops is
   * worse than one which says where it stopped.
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
   * @param theClusterHadMore Whether the reading stopped at its own page bound while the
   *          cluster still offered a next page, which makes the entries a part and not the
   *          whole. A caller which cannot pass that on says it in its log, because an
   *          incomplete answer presented as a complete one is the bug this class exists
   *          for
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
   * @param maxPages How many pages this search may cost, which a caller lowers where
   *          asking is more urgent than knowing everything
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
   * For a caller searching a set for ONE entry. It reads the pages it needs rather than
   * the whole set, which matters where the set is the iterations of a multi-instance and
   * the search runs while a workflow is being served.
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
        // a full page without a cursor to go on from: there is no way to ask further,
        // and claiming the set ends here would be a guess
        return new PagesRead<>(items, true);
      }
    }
    return new PagesRead<>(items, true);

  }

}
