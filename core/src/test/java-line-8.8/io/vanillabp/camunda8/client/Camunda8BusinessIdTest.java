package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.command.CreateProcessInstanceCommandStep1;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An instance of this line carries no business id, and the adapter says so rather than
 * sending a field the cluster answers with 400.
 * <p>
 * The point of asserting it per line is the configuration key: an application which moves
 * between lines carries one configuration, so the key is accepted here, changes nothing, and
 * the boot writes one line about it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8BusinessIdTest {

  @Test
  @DisplayName("This line has no business id")
  public void thisLineHasNoBusinessId() {

    assertFalse(Camunda8BusinessId.supportedByThisLine());

  }

  @Test
  @DisplayName("Nothing reaches the create command")
  public void nothingReachesTheCreateCommand() {

    final var command = mock(
        CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3.class,
        RETURNS_SELF);

    assertSame(command, Camunda8BusinessId.writeTo(command, "the-aggregate"));
    assertTrue(
        mockingDetails(command).getInvocations().isEmpty(),
        "the workflow is started exactly as it was before");

  }

  @Test
  @DisplayName("A configured adapter boots with the key, and gets one line saying it does nothing")
  public void theKeyIsAcceptedAndIgnored() {

    final var lines = new ArrayList<String>();
    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");

    configuration.validateAggregateIdAsBusinessId("c8", lines::add);
    assertTrue(lines.isEmpty(), "the key is off by default, so there is nothing to say");

    configuration.setAggregateIdAsBusinessId(true);
    configuration.validateAggregateIdAsBusinessId("c8", lines::add);
    assertEquals(1, lines.size());
    assertTrue(
        lines.getFirst().contains("vanillabp.adapters.c8.aggregate-id-as-business-id"),
        lines.toString());
    assertTrue(lines.getFirst().contains("no effect on this release line"), lines.toString());
    assertFalse(configuration.writesTheBusinessIdOfAnInstance());
    assertNull(configuration.businessIdOf("the-aggregate"), "and nothing is sent whatever is written");

  }

}
