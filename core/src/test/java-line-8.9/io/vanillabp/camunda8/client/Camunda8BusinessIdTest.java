package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.command.CreateProcessInstanceCommandStep1;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An instance of this line can carry a business id, and this adapter writes the workflow
 * aggregate's id into it where the application asked for that.
 * <p>
 * What the tests pin is the value rather than the plumbing: nothing reads it back, so a value
 * which is wrong is only ever seen by a person reading Operate, and the one value the cluster
 * REFUSES - a string longer than its limit - would refuse the start of a workflow.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8BusinessIdTest {

  @Test
  @DisplayName("This line has a business id")
  public void thisLineHasABusinessId() {

    assertTrue(Camunda8BusinessId.supportedByThisLine());

  }

  @Test
  @DisplayName("The value reaches the create command, and nothing reaches it where there is none")
  public void theValueReachesTheCreateCommand() {

    final var command = mock(
        CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3.class,
        RETURNS_SELF);

    Camunda8BusinessId.writeTo(command, "the-aggregate");
    verify(command).businessId("the-aggregate");

    final var untouched = mock(
        CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3.class,
        RETURNS_SELF);
    assertSame(untouched, Camunda8BusinessId.writeTo(untouched, null));
    assertTrue(mockingDetails(untouched).getInvocations().isEmpty());

  }

  @Test
  @DisplayName("Off by default, and on it writes the aggregate's id")
  public void offByDefaultAndOnItWritesTheAggregatesId() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");

    assertNull(configuration.businessIdOf("the-aggregate"), "the field is the application's until asked");

    configuration.setAggregateIdAsBusinessId(true);
    assertTrue(configuration.writesTheBusinessIdOfAnInstance());
    assertEquals("the-aggregate", configuration.businessIdOf("the-aggregate"));

  }

  @Test
  @DisplayName("An id the cluster would refuse is cut rather than refused at the start")
  public void anIdTheClusterWouldRefuseIsCut() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.setAggregateIdAsBusinessId(true);

    final var tooLong = "x".repeat(Camunda8AdapterConfiguration.BUSINESS_ID_LIMIT + 744);
    assertEquals(
        Camunda8AdapterConfiguration.BUSINESS_ID_LIMIT,
        configuration.businessIdOf(tooLong).length(),
        "the cluster refuses a longer one, and the start of a workflow is the worst place for a refusal");

    assertNull(configuration.businessIdOf(null), "an aggregate without an id writes none");
    assertNull(configuration.businessIdOf(" "), "the cluster answers 'No businessId provided' for a blank");

  }

  @Test
  @DisplayName("The boot says what the key does, once")
  public void theBootSaysWhatTheKeyDoes() {

    final var lines = new ArrayList<String>();
    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");

    configuration.validateAggregateIdAsBusinessId("c8", lines::add);
    assertTrue(lines.isEmpty(), "the key is off by default, so there is nothing to say");

    configuration.setAggregateIdAsBusinessId(true);
    configuration.validateAggregateIdAsBusinessId("c8", lines::add);
    assertEquals(1, lines.size());
    assertTrue(lines.getFirst().contains("is cut to that length"), lines.toString());

  }

}
