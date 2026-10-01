package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.camunda8.wiring.Camunda8CancelListeners;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * An instance canceled through the API, against a real cluster.
 * <p>
 * This is the test which measures the claim the unit tests can only pin down halfway: they
 * hold what the adapter writes into a model and what it makes of a job, while only a cluster
 * of the line really runs the listener when somebody terminates an instance. The two are
 * worth telling apart, because a line which stopped running the listener would otherwise show
 * up as a workflow which is canceled without a word and nothing here would turn red.
 * <p>
 * It runs on the 8.10 line and nowhere else: the <code>cancel</code> execution listener of
 * the process element arrived there, and the class is skipped rather than failed on the older
 * lines, so their run costs no container for it. That line is GA, so every pull request runs
 * this class.
 * <p>
 * The instance waits at a SERVICE task, and that was once the only shape which could be
 * canceled here. On 8.10.0-alpha5 an instance holding a Camunda-managed user task could not be
 * canceled at all: the <code>canceling</code> task-listener job was created and never handed
 * out, so the instance stayed ACTIVE and the process listener never fired, because the cluster
 * runs it only after every child element has terminated. That is camunda/camunda#58193 and it
 * is fixed from 8.10.0-rc1 on. Widening this class to a user task is open work.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@EnabledIf("theLineReportsACancelation")
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8WorkflowCanceledIT extends SpringBootTestOnTheSharedCluster {

  /**
   * Whether the release line this build belongs to reports the cancelation of an instance at
   * all. Read before the container of this class is started, so a line without the construct
   * pays nothing for a test which could not pass.
   *
   * @return Whether to run this class
   */
  static boolean theLineReportsACancelation() {

    return Camunda8CancelListeners.theProcessCanReportItsCancellation();

  }

  @Autowired
  private CanceledDockerWorkflowService workflowService;

  @Autowired
  private CanceledDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Test
  @DisplayName("An instance canceled through the API reports its end as CANCELED")
  public void aCanceledInstanceReportsItsEnd() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow(new CanceledDockerAggregate()).getId());
    CanceledDockerWorkflowService.ENDED_AS.remove(String.valueOf(aggregateId));

    awaitUntil(
        () -> openTaskOf(aggregateId) != null,
        60000,
        "the instance to reach its asynchronous task");

    final var instanceKey = theInstanceOf(aggregateId);
    clientFactoryRegistry
        .getFactory("c8")
        .getClient()
        .newCancelInstanceCommand(instanceKey)
        .send()
        .join();

    // the aggregate and not the map: the handler fills both, but the map is written inside
    // the transaction of the delivery and is therefore visible before that transaction has
    // committed, so a reader of the database would still see the aggregate as it was
    awaitUntil(
        () -> endedAsOf(aggregateId) != null,
        60000,
        "the application to have stored the end of the workflow");

    assertEquals(
        WorkflowEnd.Kind.CANCELED.name(),
        CanceledDockerWorkflowService.ENDED_AS.get(String.valueOf(aggregateId)),
        "a cancelation is reported as one, not as a completion");
    assertEquals(
        WorkflowEnd.Kind.CANCELED.name(),
        endedAsOf(aggregateId),
        "and the handler ran in a transaction of the application, so the aggregate holds it too");

  }

  /**
   * How the workflow of that aggregate ended as the aggregate itself holds it, or
   * <code>null</code> while the transaction of the delivery has not committed yet.
   */
  private String endedAsOf(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> repository.findById(aggregateId).map(CanceledDockerAggregate::getEndedAs).orElse(null));

  }

  /**
   * The job key the asynchronous task handler wrote, or <code>null</code> while the
   * instance has not reached the task yet.
   */
  private String openTaskOf(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> repository.findById(aggregateId).map(CanceledDockerAggregate::getOpenTaskId).orElse(null));

  }

  /**
   * The process instance key of the workflow of that aggregate, read from the job the
   * application is holding open.
   * <p>
   * The read goes through the search, which an exporter feeds asynchronously, so it waits
   * for the job to turn up rather than reading once.
   */
  private long theInstanceOf(
      final Long aggregateId) throws Exception {

    final var instanceKey = new AtomicLong(0L);
    awaitUntil(
        () -> {
          final var jobs = clientFactoryRegistry
              .getFactory("c8")
              .getClient()
              .newJobSearchRequest()
              .filter(filter -> filter.jobKey(Long.parseLong(openTaskOf(aggregateId))))
              .send()
              .join()
              .items();
          if (jobs.isEmpty()) {
            return Boolean.FALSE;
          }
          instanceKey.set(jobs.getFirst().getProcessInstanceKey());
          return Boolean.TRUE;
        },
        60000,
        "the search to report the job the application holds open");
    return instanceKey.get();

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final long timeoutMillis,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(200);
    }

  }

}
