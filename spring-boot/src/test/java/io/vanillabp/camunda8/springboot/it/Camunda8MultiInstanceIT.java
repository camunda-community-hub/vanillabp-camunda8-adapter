package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Multi-instance against a REAL Camunda 8 broker: the element, the index
 * and the total of every iteration reach the {@code @WorkflowTask} method, including
 * the iteration of the multi-instance SUBPROCESS a nested task runs in, and the one a
 * CALLED process was reached in.
 *
 * <p>
 * A called process named by an EXPRESSION is here too, and it is the one case the deployment
 * cannot link: which process such a call activity reaches is decided per instance. The caller
 * writes its chain into the called instance instead.
 * </p>
 *
 * <p>
 * That last part is what this engine cannot answer by itself. It puts the index of
 * an iteration into the variable {@code loopCounter} and the element into whatever
 * {@code inputElement} names, so an inner iteration shadows an outer one, and it has
 * no equivalent of {@code nrOfInstances} at all. The adapter therefore injects input
 * mappings while deploying, one set per multi-instance element, named after that
 * element - what this test proves is that the values arrive, unshadowed and in the
 * order the SPI defines.
 * </p>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8MultiInstanceIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private MiDockerWorkflowService workflowService;

  @Autowired
  private MiDockerAggregateRepository repository;

  @Autowired
  private MiCallDockerWorkflowService callWorkflowService;

  @Autowired
  private MiCallDockerAggregateRepository callRepository;

  @Autowired
  private MiFeelDockerWorkflowService feelWorkflowService;

  @Autowired
  private MiFeelDockerAggregateRepository feelRepository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(250);
    }

  }

  @Test
  @DisplayName("every iteration is handed its element, its index and its total - nested ones as well")
  public void theIterationIsReported() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    awaitUntil(
        () -> repository
            .findById(aggregateId)
            .map(MiDockerAggregate::getNested)
            .filter(nested -> nested.split(",").length == 6)
            .isPresent(),
        "all six iterations of the nested multi-instance task to have run");

    final var aggregate = repository
        .findById(aggregateId)
        .orElseThrow();

    // the index counts from 0 like Camunda 7 does, although this engine counts from 1
    assertEquals(
        "a#0/2,b#1/2",
        aggregate.getFlat(),
        "element, index and total of the flat multi-instance task");

    // two groups times three items, and every iteration knows about both levels
    assertEquals(
        "g1#0/2-x#0/3,g1#0/2-y#1/3,g1#0/2-z#2/3,g2#1/2-x#0/3,g2#1/2-y#1/3,g2#1/2-z#2/3",
        aggregate.getNested(),
        "the enclosing iteration of a nested task, which this engine shadows");

  }

  @Test
  @DisplayName("a task in a called process runs in the iteration of its caller, two levels down as well")
  public void theIterationCrossesTheCallActivity() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> callWorkflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    awaitUntil(
        () -> callRepository
            .findById(aggregateId)
            .map(MiCallDockerAggregate::getTwoLevelsDown)
            .filter(reported -> reported.split(",").length == 2)
            .isPresent(),
        "both groups to have run through the called process and the one it calls");

    final var aggregate = callRepository
        .findById(aggregateId)
        .orElseThrow();

    assertEquals(
        "g1#0/2,g2#1/2",
        aggregate.getInCalledProcess(),
        "the subprocess around the call activity is in the caller, and the task is not");

    assertEquals(
        "g1#0/2-x#0/2,g1#0/2-y#1/2,g2#1/2-x#0/2,g2#1/2-y#1/2",
        aggregate.getBothChains(),
        "a task which is multi-instance in the called process reports its own iteration and "
            + "the caller's");

    assertEquals(
        "MIC_PerGroup>MIC_ChildMiTask",
        aggregate.getChainOrder(),
        "outermost first, and the outermost one belongs to another BPMN process");

    assertEquals(
        "g1#0/2,g2#1/2",
        aggregate.getTwoLevelsDown(),
        "two call activities away from the subprocess, the iteration is still reported");

  }


  @Test
  @DisplayName("a called process named by an expression is told the iteration of its caller too")
  public void theIterationCrossesACallActivityNamedByAnExpression() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> feelWorkflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    awaitUntil(
        () -> feelRepository
            .findById(aggregateId)
            .map(MiFeelDockerAggregate::getReported)
            .filter(reported -> reported.split(",").length == 4)
            .isPresent(),
        "all four calls of the subprocess to have run");

    final var reported = feelRepository
        .findById(aggregateId)
        .orElseThrow()
        .getReported();

    assertEquals(
        "clean=MIF_Clean:c1#0/2>MIF_CallClean:p1#0/2,"
            + "clean=MIF_Clean:c1#0/2>MIF_CallClean:p2#1/2,"
            + "clean=MIF_Clean:c2#1/2>MIF_CallClean:p1#0/2,"
            + "clean=MIF_Clean:c2#1/2>MIF_CallClean:p2#1/2",
        reported,
        "the two levels of the first case belong to a model the called process never sees, and "
            + "the deployment could not say which process the call reaches");

  }

}
