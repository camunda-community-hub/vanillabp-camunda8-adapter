package io.vanillabp.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Release line <code>8.8</code>, the older bugfix line, which carries bugfixes only.
 * <p>
 * This test exists once per line, in the line's own test source directory. Only the copy of
 * the active line is compiled. It proves that the per-line build works: the profile selected
 * a client, the build filtered it into the adapter's version descriptor, and the runtime
 * reads back the line it was built for. Without it, a pin which does not reach the descriptor
 * would show up as a message which names the wrong cluster version.
 * <p>
 * Lines differ in what their cluster can do, never in what the adapter offers. So nothing
 * here asserts an API. <code>bin/api-identity.sh</code> checks that the API is the same on
 * every line.
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
