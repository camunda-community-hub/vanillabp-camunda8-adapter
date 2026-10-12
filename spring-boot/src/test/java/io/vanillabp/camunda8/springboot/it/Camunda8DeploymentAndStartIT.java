package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * End-to-end integration test of the Camunda 8 adapter against a real Camunda 8 cluster
 * (Testcontainers, the cluster of the active release line with its Elasticsearch,
 * unprotected API). It runs the <b>full two-phase workflow start through
 * {@code ProcessService#startWorkflow}</b> inside a JPA transaction with the phase-two
 * outbox:
 * <ul>
 *   <li>the BPMN {@code TestProcess} is deployed to the cluster on application startup,</li>
 *   <li>starting a workflow inside a committed transaction creates the process instance
 *       only <b>after the commit</b> (phase two) carrying the aggregate's ID as a
 *       variable named after the aggregate's ID property ({@code id} for
 *       {@link DockerAggregate}) - proven by the {@code @WorkflowTask} method of
 *       {@code DockerWorkflowService}, which the adapter's worker invokes for the service
 *       task and which receives the aggregate found by that variable, and</li>
 *   <li>a rolled-back transaction never creates an instance (no job is ever activated and
 *       the outbox entry is gone).</li>
 * </ul>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8DeploymentAndStartIT extends SpringBootTestOnTheSharedCluster {

  /**
   * What a probe is asked about.
   */
  private static final WorkflowScope SCOPE = WorkflowScope
      .of("test-module", "TestProcess");

  private static final String JOB_TYPE = "test-job";

  @Autowired
  private ProcessService<DockerAggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DataSource dataSource;

  /**
   * What this class asks about the phase-two outbox. It comes from the platform's test
   * tools, so the name of the table stays out of this class.
   * <p>
   * This application runs the outbox table which VanillaBP writes itself. So the reader is
   * told which table to read, and it does not look for the one which is there.
   */
  private PhaseTwoOutboxReader outbox;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private Camunda8ProcessService<DockerAggregate> camunda8ProcessService;

  /**
   * The probes take the aggregate's persistence, because the aggregate-ID VARIABLE is named
   * after its ID attribute. The search below filters by that name.
   */
  private static final AggregatePersistenceAware<DockerAggregate> AGGREGATE_PERSISTENCE = new AggregatePersistenceAware<>() {

    @Override
    public Class<DockerAggregate> getAggregateClass() {

      return DockerAggregate.class;

    }

    @Override
    public String getAggregateIdName() {

      return "id";

    }

  };

  @BeforeEach
  void resetRecording() {

    DockerWorkflowService.ACTIVATED_AGGREGATE_IDS.clear();

  }

  @BeforeEach
  void takeTheOutbox() {

    outbox = PhaseTwoOutboxReader.ofTheVanillaBpOutbox(dataSource);

  }

  private int countOutboxEntries() {

    return outbox
        .entries()
        .size();

  }

  @Test
  @DisplayName("startWorkflow in a committed transaction creates the instance only after commit with the aggregateId variable")
  public void instanceAppearsOnlyAfterCommit() throws Exception {

    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new DockerAggregate();
      aggregate.setContent("commit-test");
      final var saved = processService.startWorkflow(aggregate);
      // phase two runs only after commit. Within the transaction no instance exists yet,
      // so the worker cannot have seen anything
      assertTrue(DockerWorkflowService.ACTIVATED_AGGREGATE_IDS.isEmpty(),
          "no process instance may exist before the transaction commits");
      return saved;
    });

    final var expectedAggregateId = String.valueOf(attached.getId());

    // after the commit, the phase-two outbox dispatches the create. Later the worker
    // activates the service task of the started instance, which carries the aggregateId
    final var found = awaitAggregateId(expectedAggregateId, 20000);
    assertTrue(found,
        "expected a process instance with aggregateId '"
            + expectedAggregateId
            + "' after commit, but the worker saw "
            + DockerWorkflowService.ACTIVATED_AGGREGATE_IDS);

  }

  @Test
  @DisplayName("startWorkflow in a rolled-back transaction never creates an instance")
  public void noInstanceAfterRollback() throws Exception {

    final var entriesBefore = countOutboxEntries();

    final var exception = assertThrowsExactly(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {
          final var aggregate = new DockerAggregate();
          aggregate.setContent("rollback-test");
          processService.startWorkflow(aggregate);
          throw new RuntimeException("test rollback");
        }));
    assertEquals("test rollback", exception.getMessage());

    // the outbox entry was enlisted in the rolled-back transaction, so it is gone
    assertEquals(entriesBefore, countOutboxEntries());

    // wait well beyond the outbox poll interval. No instance may ever be created, so the
    // worker must never activate a job.
    // 3000ms is coupled to 'vanillabp.outbox.poll-interval: PT0.5S' in camunda8-it.yaml.
    // It spans several poll cycles. So a phase-two dispatch WOULD have happened if the
    // rollback had left an outbox entry behind. Do not shrink one without the other
    Thread.sleep(3000);
    assertTrue(DockerWorkflowService.ACTIVATED_AGGREGATE_IDS.isEmpty(),
        "no process instance may be created on rollback, but the worker saw "
            + DockerWorkflowService.ACTIVATED_AGGREGATE_IDS);

  }

  @Test
  @DisplayName("a workflow nobody started is unknown to both probes, because the cluster really is searched")
  public void aWorkflowNobodyStartedIsUnknownToBothProbes() {

    final var neverStartedAggregateId = "no-such-aggregate";

    // both probes search, and an empty result is an answer: no BPMS knows this workflow.
    // That answer lets the core try the next adapter. Neither of them may guess ACTIVE
    // here. Then the election would route an operation to a BPMS which never held the
    // workflow. And the re-dispatch probe would SKIP a recovered start and so lose it
    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        camunda8ProcessService.awarenessOfWorkflow(SCOPE, AGGREGATE_PERSISTENCE, neverStartedAggregateId));
    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        camunda8ProcessService.awarenessOfWorkflowForRedispatch(SCOPE, AGGREGATE_PERSISTENCE, neverStartedAggregateId));

  }

  private boolean awaitAggregateId(
      final String aggregateId,
      final long timeoutMillis) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (DockerWorkflowService.ACTIVATED_AGGREGATE_IDS.contains(aggregateId)) {
        return true;
      }
      Thread.sleep(200);
    }
    return DockerWorkflowService.ACTIVATED_AGGREGATE_IDS.contains(aggregateId);

  }

}
