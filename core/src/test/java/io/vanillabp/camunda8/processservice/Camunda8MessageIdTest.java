package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The id this adapter hands the cluster for a correlated message. It is what the cluster
 * deduplicates by for as long as the message lives, so its shape decides which
 * publications the cluster silently drops - a second net next to VanillaBP's own, and a
 * longer one.
 * <p>
 * Two shapes are pinned here rather than one, because the difference between them IS the
 * feature: with the activation, the three elements of a multi-instance call activity reach
 * the cluster as three messages; without it - a correlation planned outside any activation
 * - the id is exactly what every earlier version sent, so nothing about a REST endpoint's
 * correlations changes.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8MessageIdTest {

  @Test
  @DisplayName("Outside an activation the id is the one this adapter always sent")
  public void withoutAnActivationTheIdIsUnchanged() {

    assertEquals(
        "module|Process|4711|OfferRequested|partner-42",
        Camunda8ProcessService
            .messageIdOf("module", "Process", "4711", "OfferRequested", "partner-42", null));

  }

  @Test
  @DisplayName("Inside an activation the id names it, so siblings are three messages")
  public void theActivationIsAppended() {

    assertEquals(
        "module|Process|4711|OfferRequested|partner-42|element-1",
        Camunda8ProcessService
            .messageIdOf("module", "Process", "4711", "OfferRequested", "partner-42", "element-1"));

  }

  @Test
  @DisplayName("Three multi-instance siblings of one aggregate derive three ids")
  public void siblingsOfOneAggregateDeriveDifferentIds() {

    // everything but the activation is equal: a called process is a secondary workflow of
    // the SAME aggregate, and a correlation id read from business data does not have to
    // differ between the elements
    final var first = Camunda8ProcessService
        .messageIdOf("module", "Process", "4711", "OfferRequested", "partner-42", "element-1");
    final var second = Camunda8ProcessService
        .messageIdOf("module", "Process", "4711", "OfferRequested", "partner-42", "element-2");
    final var third = Camunda8ProcessService
        .messageIdOf("module", "Process", "4711", "OfferRequested", "partner-42", "element-3");

    assertNotEquals(first, second);
    assertNotEquals(second, third);
    assertNotEquals(first, third);

  }

  @Test
  @DisplayName("A redelivery of one activation derives the same id, which is what must not change")
  public void oneActivationAlwaysDerivesOneId() {

    // the guarantee the activation must not cost: an at-least-once dispatch of the same
    // entry publishes the same id, and the cluster drops the repetition - which is the
    // one thing this net is there for
    assertEquals(
        Camunda8ProcessService
            .messageIdOf("module", "Process", "4711", "OfferRequested", "partner-42", "element-1"),
        Camunda8ProcessService
            .messageIdOf("module", "Process", "4711", "OfferRequested", "partner-42", "element-1"));

  }

}
