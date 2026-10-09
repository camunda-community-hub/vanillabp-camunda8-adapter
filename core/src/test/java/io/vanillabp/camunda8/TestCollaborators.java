package io.vanillabp.camunda8;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * What the platform hands the adapter, for tests which need the adapter and not the
 * registration. The core standing in for both halves of the task SPI is given per test;
 * the rest are mocks nobody calls unless the test says so.
 */
public final class TestCollaborators {

  private TestCollaborators() {
    // static helper
  }

  /**
   * @param <T> A double playing both halves of the task SPI
   * @param core The double
   * @return A complete set built around it
   */
  public static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators of(
      final T core) {

    return of(core, mock(NameClashAvoidanceSupport.class));

  }

  /**
   * @param <T> A double playing both halves of the task SPI
   * @param core The double
   * @param scoping What the test wants the name-clash avoidance to answer
   * @return A complete set built around them
   */
  public static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators of(
      final T core,
      final NameClashAvoidanceSupport scoping) {

    return of(core, scoping, mock(WorkflowEndedInvoker.class));

  }

  /**
   * @param <T> A double playing both halves of the task SPI
   * @param core The double
   * @param workflowEnded What the test wants the core to answer about the end of a
   *          workflow - whether it has to be reported decides what the wiring puts into
   *          the model
   * @return A complete set built around them
   */
  public static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators of(
      final T core,
      final WorkflowEndedInvoker workflowEnded) {

    return of(core, mock(NameClashAvoidanceSupport.class), workflowEnded);

  }

  /**
   * @param <T> A double playing both halves of the task SPI
   * @param core The double
   * @param scoping What the test wants the name-clash avoidance to answer
   * @param bpmsInitiatedStarts The double the test reads the start reports of
   * @return A complete set built around them
   */
  public static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators of(
      final T core,
      final NameClashAvoidanceSupport scoping,
      final BpmsInitiatedStartInvoker bpmsInitiatedStarts) {

    return of(core, scoping, mock(WorkflowEndedInvoker.class), bpmsInitiatedStarts);

  }

  private static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators of(
      final T core,
      final NameClashAvoidanceSupport scoping,
      final WorkflowEndedInvoker workflowEnded) {

    return of(core, scoping, workflowEnded, startingAWorkflowEverywhere());

  }

  /**
   * A start entry of the core which answers what the core answers for a process that is no
   * called process. A plain mock would answer <code>false</code> to
   * <code>startsAWorkflowOfItsOwn</code>, and every process would look like a called one.
   *
   * @return The mock
   */
  public static BpmsInitiatedStartInvoker startingAWorkflowEverywhere() {

    final var bpmsInitiatedStarts = mock(BpmsInitiatedStartInvoker.class);
    when(bpmsInitiatedStarts.startsAWorkflowOfItsOwn(anyString(), anyString())).thenReturn(true);
    return bpmsInitiatedStarts;

  }

  /**
   * @param <T> A double playing both halves of the task SPI
   * @param core The double
   * @param workflowEnded What the test wants the core to answer about the end of a workflow
   * @param bpmsInitiatedStarts What the test wants the core to answer about a start
   * @return A complete set built around them
   */
  public static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators of(
      final T core,
      final WorkflowEndedInvoker workflowEnded,
      final BpmsInitiatedStartInvoker bpmsInitiatedStarts) {

    return of(core, mock(NameClashAvoidanceSupport.class), workflowEnded, bpmsInitiatedStarts);

  }

  private static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators of(
      final T core,
      final NameClashAvoidanceSupport scoping,
      final WorkflowEndedInvoker workflowEnded,
      final BpmsInitiatedStartInvoker bpmsInitiatedStarts) {

    return AdapterCollaborators
        .forAdapter("c8")
        .workflowTaskWiring(core)
        .workflowTaskInvoker(core)
        .scoping(scoping)
        .workflowAggregateSync(mock(WorkflowAggregateSync.class))
        .preCommitRegistrar(mock(PreCommitRegistrar.class))
        .workflowEndedInvoker(workflowEnded)
        .bpmsInitiatedStartInvoker(bpmsInitiatedStarts)
        .build();

  }

}
