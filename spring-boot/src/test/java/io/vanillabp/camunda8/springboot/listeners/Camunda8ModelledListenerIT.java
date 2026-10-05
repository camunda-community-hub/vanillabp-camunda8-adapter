package io.vanillabp.camunda8.springboot.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Whether a listener somebody modelled is really served on a real cluster while
 * {@code allow-listeners} is on for its process.
 * <p>
 * A cluster is what this needs and nothing else would do. The cluster creates a job per
 * listener, a worker has to be subscribed to the listener's job type for the workflow to move at
 * all, and the workflow module runs under {@code name-clash-avoidance: use-prefix} - so the job
 * type the cluster knows carries the module and the process, while the methods below are named
 * after what the modeller typed. Without a cluster nothing proves that the prefix and its way
 * back meet.
 * <p>
 * The scenario brings its own application, its own configuration file and its own resources
 * location, see {@link ListenerTestApplication}: a model carrying a listener does not deploy
 * without the key, so no other integration test of this module may see it.
 * <p>
 * The model has a gateway behind the service task which reads a flag only the task's
 * <code>end</code> listener sets. That is the second thing a cluster is needed for: whether what
 * a listener method wrote into the workflow aggregate really reaches the process instance.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = ListenerTestApplication.class,
    properties = "spring.config.name=camunda8-listeners-it")
public class Camunda8ModelledListenerIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private ListenerDockerWorkflowService workflowService;

  @Autowired
  private ListenerDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("Every modelled listener reaches a method, and the workflow moves past them")
  public void everyListenerReachesAMethod() throws Exception {

    final var aggregate = runAWorkflow(
        "the listeners did not all reach a method",
        Camunda8ModelledListenerIT::everyListenerRan);

    assertTrue(aggregate.isTheWorkWasDone(), "the ordinary task ran as it always did");

  }

  @Test
  @DisplayName("What an 'end' execution listener wrote steers the gateway behind its element")
  public void whatAnEndListenerWroteReachesTheProcess() throws Exception {

    final var aggregate = runAWorkflow(
        "the gateway behind the task never decided",
        candidate -> candidate.isTheProcessSawTheAudit() || candidate.isTheProcessMissedTheAudit());

    assertTrue(
        aggregate.isTheProcessSawTheAudit(),
        "the gateway reads a flag which only the 'end' listener of the task ahead of it sets, and "
            + "the process took the other flow: the completion of that listener did not carry the "
            + "shared values of the workflow aggregate");
    assertFalse(
        aggregate.isTheProcessMissedTheAudit(),
        "and the flow for the lost value was not taken");

  }

  private static boolean everyListenerRan(
      final ListenerDockerAggregate aggregate) {

    return aggregate.isTheWorkWasPrepared() && aggregate.isTheWorkWasAudited() && aggregate.isTheOrderWasArchived();

  }

  /**
   * Starts a workflow and waits until the aggregate in the database says what the test is
   * about.
   *
   * @param whatWasMissing What the failure message says did not happen
   * @param done Whether the aggregate has reached the state the test waits for
   * @return The aggregate as the database holds it
   */
  private ListenerDockerAggregate runAWorkflow(
      final String whatWasMissing,
      final Predicate<ListenerDockerAggregate> done) throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    final var deadline = System.currentTimeMillis() + 150_000;
    while (System.currentTimeMillis() < deadline) {
      final var aggregate = transactionTemplate
          .execute(status -> repository.findById(aggregateId).orElseThrow());
      if (done.test(aggregate)) {
        return aggregate;
      }
      Thread.sleep(1000);
    }

    final var aggregate = transactionTemplate
        .execute(status -> repository.findById(aggregateId).orElseThrow());
    fail(
        ("%s within 150 seconds: prepared %s, the work done %s, audited %s, archived %s, the "
            + "process saw the audit %s, the process missed it %s. A listener job nothing serves "
            + "stops the workflow there with no incident and no message, which is what this key "
            + "exists for")
            .formatted(
                whatWasMissing,
                aggregate.isTheWorkWasPrepared(),
                aggregate.isTheWorkWasDone(),
                aggregate.isTheWorkWasAudited(),
                aggregate.isTheOrderWasArchived(),
                aggregate.isTheProcessSawTheAudit(),
                aggregate.isTheProcessMissedTheAudit()));
    return aggregate;

  }

}
