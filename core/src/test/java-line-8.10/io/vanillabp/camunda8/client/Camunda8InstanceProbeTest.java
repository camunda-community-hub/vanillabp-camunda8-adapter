package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.AssignProcessInstanceBusinessIdCommandStep1;
import io.camunda.client.api.command.ModifyProcessInstanceCommandStep1;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which of the two commands the instance probe sends on this line.
 * <p>
 * This line has both, and the business id decides. It is asserted here rather than left to
 * the caller, because until the key of decision 37 existed the assignment was unreachable:
 * the answer which picks it was hard-coded to <code>false</code>, so nothing ever went down
 * that branch. Now it can, and which command leaves the adapter is the difference between a
 * question the cluster refuses and one it carries out.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8InstanceProbeTest {

  private static final long INSTANCE = 2251799813685240L;

  private static final String RESERVED_ELEMENT = "vanillabp-existence-probe";

  private final CamundaClient client = mock(CamundaClient.class);

  private final AssignProcessInstanceBusinessIdCommandStep1 assignment = mock(
      AssignProcessInstanceBusinessIdCommandStep1.class,
      RETURNS_SELF);

  private final AssignProcessInstanceBusinessIdCommandStep1.AssignProcessInstanceBusinessIdCommandStep2 assigned = mock(
      AssignProcessInstanceBusinessIdCommandStep1.AssignProcessInstanceBusinessIdCommandStep2.class,
      RETURNS_SELF);

  private final ModifyProcessInstanceCommandStep1 modification = mock(
      ModifyProcessInstanceCommandStep1.class,
      RETURNS_SELF);

  private final ModifyProcessInstanceCommandStep1.ModifyProcessInstanceCommandStep3 modified = mock(
      ModifyProcessInstanceCommandStep1.ModifyProcessInstanceCommandStep3.class,
      RETURNS_SELF);

  /**
   * Both commands, ready to be sent and answered with nothing. What the cluster answers is
   * the caller's business; what is asked here is which command was built at all.
   */
  private void bothCommandsAreThere() {

    // the futures are built BEFORE they are handed to a stub: mocking inside the argument
    // of when(...) leaves Mockito in the middle of a stubbing and it says so
    final var assignmentWasSent = aFutureAnsweringNothing();
    final var modificationWasSent = aFutureAnsweringNothing();
    Mockito.lenient().when(assignment.businessId(Mockito.anyString())).thenReturn(assigned);
    Mockito.lenient().when(assigned.send()).thenReturn(assignmentWasSent);
    Mockito.lenient().when(client.newAssignProcessInstanceBusinessIdCommand(INSTANCE)).thenReturn(assignment);
    Mockito.lenient().when(modification.activateElement(Mockito.anyString())).thenReturn(modified);
    Mockito.lenient().when(modified.send()).thenReturn(modificationWasSent);
    Mockito.lenient().when(client.newModifyProcessInstanceCommand(INSTANCE)).thenReturn(modification);

  }

  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private static CamundaFuture aFutureAnsweringNothing() {

    final CamundaFuture future = mock(CamundaFuture.class);
    Mockito.lenient().when(future.join()).thenReturn(null);
    return future;

  }

  @Test
  @DisplayName("With a business id the assignment is sent, and it carries that id")
  public void withABusinessIdTheAssignmentIsSent() {

    bothCommandsAreThere();

    Camunda8InstanceProbe.askTheEngine(client, INSTANCE, RESERVED_ELEMENT, "the-aggregate");

    verify(assignment).businessId("the-aggregate");
    assertTrue(
        mockingDetails(modification).getInvocations().isEmpty(),
        "an instance carrying this adapter's id refuses the assignment, which is the question - "
            + "no modification is sent beside it");

  }

  @Test
  @DisplayName("Without one the modification is sent, naming the reserved element")
  public void withoutOneTheModificationIsSent() {

    bothCommandsAreThere();

    Camunda8InstanceProbe.askTheEngine(client, INSTANCE, RESERVED_ELEMENT, null);

    verify(modification).activateElement(RESERVED_ELEMENT);
    assertTrue(
        mockingDetails(assignment).getInvocations().isEmpty(),
        "an instance which carries no business id of this adapter would ACCEPT an assignment, so "
            + "none is sent where the adapter does not write that field");

  }

}
