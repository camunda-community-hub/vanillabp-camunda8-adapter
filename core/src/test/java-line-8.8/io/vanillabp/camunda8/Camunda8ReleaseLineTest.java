package io.vanillabp.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Release line <code>8.8</code>, the previous GA line, which carries bugfixes only.
 * <p>
 * This test exists once per line, in the line's own test source directory, and only the
 * active line's copy is compiled. It is what proves the per-line build itself: the profile
 * selected a client, the build filtered it into the adapter's version descriptor, and the
 * runtime reads back the line it was actually built for. A pin that does not reach the
 * descriptor would otherwise surface as a message naming the wrong cluster version.
 * <p>
 * A line differs in what its cluster can do, never in what the adapter offers, so nothing
 * here asserts an API. That the API is identical across lines is checked by
 * <code>bin/api-identity.sh</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ReleaseLineTest {

  @Test
  @DisplayName("the build belongs to line 8.8 and knows the client it pinned")
  public void theLineIsTheOneTheProfileSelected() {

    assertEquals("8.8", Camunda8ReleaseLine.id());

    assertTrue(
        Camunda8ReleaseLine.clientVersion().startsWith("8.8."),
        "line 8.8 has to be built against a Camunda client 8.8.x, but the descriptor says '"
            + Camunda8ReleaseLine.clientVersion()
            + "'");

  }

}
