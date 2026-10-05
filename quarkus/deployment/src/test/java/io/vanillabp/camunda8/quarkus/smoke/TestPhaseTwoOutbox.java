package io.vanillabp.camunda8.quarkus.smoke;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A {@link PhaseTwoOutbox} stub for smoke tests booting WITHOUT a database: the
 * Camunda 8 adapter sends everything through phase two, and the
 * platform wants the outbox RESOLVABLE at startup. The smoke
 * tests never start workflows - any usage of the stub fails loudly.
 */
@ApplicationScoped
public class TestPhaseTwoOutbox implements PhaseTwoOutbox {

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    throw new UnsupportedOperationException("no outbox in this test");

  }

}
