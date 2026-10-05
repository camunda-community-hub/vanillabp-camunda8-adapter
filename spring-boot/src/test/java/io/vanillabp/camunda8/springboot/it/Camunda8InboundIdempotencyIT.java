package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.adapter.migration.processservice.DeliveryRecords;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader.Delivery;

/**
 * A REDELIVERY of a task VanillaBP already processed, against a real Camunda 8
 * cluster: it must not run the <code>&#64;WorkflowTask</code> method again.
 * <p>
 * Forcing a redelivery of a task whose result the cluster already learned is impossible -
 * the cluster would have to lose a completion. What can be forced is the case of an
 * asynchronous task: a <code>&#64;TaskId</code> method leaves the job open, the adapter
 * renews its lock for <code>async-task-lock-renewal</code>, and once that window passes
 * the cluster hands the very same job out again. This test therefore runs with a renewal
 * window of two seconds and waits several of those windows out: the handler must have run
 * exactly once, every further delivery being answered from the record VanillaBP wrote in
 * the handler's own transaction.
 * <p>
 * The second half of the arrangement is the retention of the delivery records, set
 * to ten seconds here, well below the runtime of this test and five renewal
 * windows wide. That is the relation which matters - the window has to sit
 * clearly below the retention, because the record is what answers the redelivery which
 * renews the lock. A configuration violating it does not even boot.
 * <p>
 * The cluster and the delivery log are both here, so what a record CARRIES is asserted
 * here too: the element id of the BPMN element and the process instance key. Both are read
 * out of the table rather than out of the invocation context, because a value only the
 * context knows is the defect that assertion is about.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = {
        "spring.config.name=camunda8-it",
        // the open job's lock expires after two seconds, so the cluster redelivers it
        "vanillabp.adapters.c8.async-task-lock-renewal=PT2S",
        // five renewal windows: the record has to answer every one of them
        "vanillabp.delivery.retention=PT10S",
        // deliberately far away from the number above: the delivery log has to read its
        // own retention, and against a real cluster this is where reading the outbox one
        // would show
        "vanillabp.outbox.retention=P7D"
    })
public class Camunda8InboundIdempotencyIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private TaskDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  /**
   * What this class asks about the delivery log. The reader belongs to the platform, so
   * this class names neither the table nor its columns.
   */
  private TaskDeliveryLogReader deliveryLog;

  @BeforeEach
  public void takeTheDeliveryLog() {

    deliveryLog = TaskDeliveryLogReader.of(dataSource);

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

  private int invocations(
      final Long aggregateId) {

    final var counter = TaskDockerWorkflowService.INVOCATIONS
        .get("asyncTask:"
            + aggregateId);
    return counter != null
        ? counter.get()
        : 0;

  }

  @Test
  @DisplayName("A job the cluster hands out again does not run the handler a second time")
  public void redeliveredJobsSkipTheHandler() throws Exception {

    // the core logs every skipped redelivery - the proof that the cluster really
    // handed the job out again (an unnoticed redelivery and none at all would look
    // the same on the aggregate)
    final var skippedDeliveries = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    skippedDeliveries.start();
    // a repeated delivery is answered from the core's delivery records, which is where
    // the message about it comes from
    final var coreLogger = (ch.qos.logback.classic.Logger) LoggerFactory
        .getLogger(DeliveryRecords.class);
    coreLogger.addAppender(skippedDeliveries);

    try {
      final var aggregateId = transactionTemplate.execute(status -> repository
          .save(new TaskDockerAggregate())
          .getId());
      // the counters are static and the aggregate IDs of the test classes overlap
      TaskDockerWorkflowService.INVOCATIONS
          .remove("asyncTask:"
              + aggregateId);

      // AsyncProcess parks at a @TaskId task: the job stays open and its lock expires
      // after the configured two seconds
      clientFactoryRegistry
          .getFactory("c8")
          .getClient()
          .newCreateInstanceCommand()
          .bpmnProcessId("test-app__AsyncProcess")
          .latestVersion()
          .variable("id", String.valueOf(aggregateId))
          .send()
          .join();

      awaitUntil(
          () -> invocations(aggregateId) == 1,
          60000,
          "the asynchronous task to be invoked once");

      // the delivery was recorded in the handler's transaction, with the outcome the
      // adapter reports again on every redelivery. Awaited, not asserted right away: the
      // counter above is incremented INSIDE the handler, while the record is written
      // after it returned and becomes visible with the commit - reading it immediately is
      // a race the test lost on a CI runner
      awaitUntil(
          () -> asyncTaskDeliveriesOf(aggregateId)
              .stream()
              .anyMatch(record -> "COMPLETION_PENDING".equals(record.outcome())),
          30000,
          "the delivery of the asynchronous task to be recorded as pending completion");

      // wait out several lock windows - each expiry hands the job out again
      awaitUntil(
          () -> messagesOf(skippedDeliveries)
              .stream()
              .anyMatch(message -> message.contains("Skipping the repeated delivery")),
          30000,
          "the cluster to redeliver the dormant job");

      assertEquals(
          1,
          invocations(aggregateId),
          "the redelivered job must not run the handler again");
      assertEquals(
          "async-open",
          repository.findById(aggregateId).orElseThrow().getResults(),
          "the handler's result was appended once, although the job arrived more than once");
      assertTrue(
          repository.findById(aggregateId).orElseThrow().getTaskId() != null,
          "the job key was committed as the task id");

    } finally {
      coreLogger.detachAppender(skippedDeliveries);
      skippedDeliveries.stop();
    }

  }

  @Test
  @DisplayName("The record of a delivery names the BPMN element and the process instance")
  public void theRecordNamesTheElementAndTheWorkflow() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());

    // AsyncProcess parks at a @TaskId task, so the record of its delivery is written and
    // stays. Its element id and its job type differ, which is what makes the two fields
    // tell apart here
    final var processInstance = clientFactoryRegistry
        .getFactory("c8")
        .getClient()
        .newCreateInstanceCommand()
        .bpmnProcessId("test-app__AsyncProcess")
        .latestVersion()
        .variable("id", String.valueOf(aggregateId))
        .send()
        .join();

    // looked up by the process instance key rather than by the aggregate: the classes of
    // this module share one in-memory database and their aggregate ids overlap, while a
    // process instance key is the cluster's and belongs to this workflow alone. That the
    // row is found at all is therefore already the assertion about the workflow id
    final var elements = new java.util.concurrent.atomic.AtomicReference<List<String>>(List.of());
    awaitUntil(
        () -> {
          elements.set(asyncTaskElementsOfWorkflow(processInstance.getProcessInstanceKey()));
          return !elements.get().isEmpty();
        },
        60000,
        "the delivery of the asynchronous task to be recorded under its process instance");

    assertEquals(
        List.of("AP_task"),
        elements.get(),
        "the element id a modeller wrote, which is not the job type the task definition holds");

  }

  /**
   * What the log wrote down about the asynchronous task of one workflow aggregate. Read by
   * aggregate AND by task definition: every integration test class of this repository
   * shares the in-memory database, and their aggregate IDs start at 1 just like ours.
   *
   * @param aggregateId The workflow aggregate this test started
   * @return Those records, empty while the log holds none
   */
  private List<Delivery> asyncTaskDeliveriesOf(
      final Long aggregateId) {

    return deliveryLog
        .deliveries()
        .stream()
        .filter(record -> String.valueOf(aggregateId).equals(record.aggregateId()))
        .filter(record -> "asyncTask".equals(record.taskDefinition()))
        .toList();

  }

  /**
   * The elements the log names for the asynchronous task of one workflow.
   *
   * @param processInstanceKey The cluster's own key of the running instance
   * @return Those element ids, empty while the log holds no record of that task
   */
  private List<String> asyncTaskElementsOfWorkflow(
      final long processInstanceKey) {

    return deliveryLog
        .deliveries()
        .stream()
        .filter(record -> String.valueOf(processInstanceKey).equals(record.workflowId()))
        .filter(record -> "asyncTask".equals(record.taskDefinition()))
        .map(Delivery::bpmnElementId)
        .toList();

  }

  private static List<String> messagesOf(
      final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {

    return List
        .copyOf(appender.list)
        .stream()
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }

}
