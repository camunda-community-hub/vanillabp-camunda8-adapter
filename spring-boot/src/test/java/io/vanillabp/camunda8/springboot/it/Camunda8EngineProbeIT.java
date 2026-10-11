package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The engine answers about an instance long before the search does, against a real cluster.
 * <p>
 * The numbers behind this were measured on 8.10.0-alpha5, 8.9.19 and 8.8.37. The create
 * answered after 10 ms. The engine said "this instance exists" after 16 to 19 ms. The search
 * found it after 167 to 1324 ms. This class does not assert the timing, because a test
 * runner cannot promise it. It asserts the ANSWERS. A fresh instance reads as ACTIVE while the
 * search may not know it yet. Everything the engine has forgotten still reads the way the
 * search reads it.
 * <p>
 * It runs on every line, because the answers were measured on every line.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8EngineProbeIT extends SpringBootTestOnTheSharedCluster {

  private static final WorkflowScope SCOPE = WorkflowScope.of("test-app", "CancelableProcess");

  /**
   * The probes take the aggregate's persistence, because the aggregate-ID VARIABLE is named
   * after its ID attribute. The search filters by that name.
   */
  private static final AggregatePersistenceAware<CanceledDockerAggregate> AGGREGATE_PERSISTENCE = new AggregatePersistenceAware<>() {

    @Override
    public Class<CanceledDockerAggregate> getAggregateClass() {

      return CanceledDockerAggregate.class;

    }

    @Override
    public String getAggregateIdName() {

      return "id";

    }

  };

  @Autowired
  private CanceledDockerWorkflowService workflowService;

  @Autowired
  private CanceledDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private ApplicationContext applicationContext;

  @Test
  @DisplayName("A workflow the engine holds reads as ACTIVE")
  public void aRunningInstanceIsActive() throws Exception {

    final var aggregateId = startAndWaitForTheTask();
    final var instanceKey = theInstanceOf(aggregateId);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService()
            .awarenessOfWorkflow(SCOPE, AGGREGATE_PERSISTENCE, aggregateId, String.valueOf(instanceKey)),
        "the engine holds the instance and says so");

  }

  @Test
  @DisplayName("The engine answers where the search of the same moment does not")
  public void theEngineAnswersWhereTheSearchDoesNot() throws Exception {

    // this is no race against the exporter, for one reason. The key names a live instance,
    // while the aggregate names a workflow nobody ever started. The search finds nothing for
    // that aggregate and never will. So an ACTIVE can only have come from the engine. The
    // contract says exactly this: the key is a hint, and it makes the yes come sooner
    final var aggregateId = startAndWaitForTheTask();
    final var instanceKey = theInstanceOf(aggregateId);
    final var anAggregateNobodyStarted = "an-aggregate-nobody-started";

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService().awarenessOfWorkflow(SCOPE, AGGREGATE_PERSISTENCE, anAggregateNobodyStarted),
        "the search alone knows nothing about that aggregate");
    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService()
            .awarenessOfWorkflow(
                SCOPE, AGGREGATE_PERSISTENCE, anAggregateNobodyStarted, String.valueOf(instanceKey)),
        "and with the key of a live instance the engine answers before the search is asked");

  }

  @Test
  @DisplayName("A workflow the engine has forgotten still reads as ended, through the search")
  public void aCanceledInstanceStillReadsAsEnded() throws Exception {

    final var aggregateId = startAndWaitForTheTask();
    final var instanceKey = theInstanceOf(aggregateId);

    clientFactoryRegistry
        .getFactory("c8")
        .getClient()
        .newCancelInstanceCommand(instanceKey)
        .send()
        .join();

    // the engine forgets a canceled instance at once, while the search needs a moment to
    // agree. This test is about the answer which the search gives
    awaitUntil(
        () -> processService()
            .awarenessOfWorkflow(SCOPE, AGGREGATE_PERSISTENCE, aggregateId,
                String.valueOf(instanceKey)) == WorkflowAwareness.COMPLETED,
        60000,
        "the search to report the canceled workflow as ended");

  }

  @Test
  @DisplayName("A key no workflow of this cluster ever had is unknown, not active")
  public void aKeyWhichNeverExistedIsUnknown() {

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService().awarenessOfWorkflow(SCOPE, AGGREGATE_PERSISTENCE, "an-aggregate-nobody-started",
            "2251799813000001"),
        "which is the answer the election needs to move on to the next BPMS");

  }

  @SuppressWarnings("unchecked")
  private Camunda8ProcessService<CanceledDockerAggregate> processService() {

    return (Camunda8ProcessService<CanceledDockerAggregate>) applicationContext
        .getBean("Camunda8_ProcessService_c8");

  }

  private Long startAndWaitForTheTask() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow(new CanceledDockerAggregate()).getId());
    awaitUntil(
        () -> openTaskOf(aggregateId) != null,
        60000,
        "the instance to reach its asynchronous task");
    return aggregateId;

  }

  private String openTaskOf(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> repository.findById(aggregateId).map(CanceledDockerAggregate::getOpenTaskId).orElse(null));

  }

  /**
   * The process instance key of the workflow of that aggregate. It is read from the job which
   * the application holds open.
   * <p>
   * The read goes through the search, and an exporter feeds the search asynchronously. That
   * is the very lag this test class is about. So the method waits for the job to turn up and
   * does not read only once. The tests then measure the ANSWER of the election, not how fast
   * the exporter was.
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
