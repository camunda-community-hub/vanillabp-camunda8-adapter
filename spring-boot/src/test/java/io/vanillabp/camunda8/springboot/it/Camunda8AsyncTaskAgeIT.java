package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.camunda8.wiring.Camunda8JobHandler;
import io.vanillabp.integration.adapter.migration.processservice.DeliveryRecords;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A task nobody ever completes, against a real Camunda 8 cluster.
 * <p>
 * The renewal of an open job's lock is driven by the cluster's own redelivery, so nothing
 * in VanillaBP ever asks whether that task is still expected - except the age of its
 * delivery record. This test lets a <code>&#64;TaskId</code> handler leave a task open,
 * sets the maximum age to one second and the renewal window to two, and asks for the
 * reaction this BPMS can offer beyond a log line: with
 * <code>async-task-max-age-action: incident</code> the adapter stops renewing and fails
 * the job with no retries left, which is what makes the cluster raise an incident.
 * <p>
 * What is asserted is what the operator would see: the handler ran exactly once (the
 * incident is not a redelivery), the core reported the age once, and the adapter named
 * the aggregate and the age in the message the incident carries.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = {
        "spring.config.name=camunda8-it",
        // the open job's lock expires after two seconds, so the cluster redelivers it
        "vanillabp.adapters.c8.async-task-lock-renewal=PT2S",
        // and by then the task is already older than it may be
        "vanillabp.delivery.max-task-age=PT1S", "vanillabp.adapters.c8.async-task-max-age-action=incident"
    })
public class Camunda8AsyncTaskAgeIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private TaskDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

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
  @DisplayName("A task older than the maximum age is reported once and fails its job into an incident")
  public void anOverdueTaskEndsInAnIncident() throws Exception {

    // the age of an open task is measured and reported by the core's delivery records
    final var coreMessages = appenderOn(DeliveryRecords.class);
    final var adapterMessages = appenderOn(Camunda8JobHandler.class);

    try {
      final var aggregateId = transactionTemplate.execute(status -> repository
          .save(new TaskDockerAggregate())
          .getId());
      TaskDockerWorkflowService.INVOCATIONS
          .remove("asyncTask:"
              + aggregateId);

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

      awaitUntil(
          () -> messagesOf(adapterMessages)
              .stream()
              .anyMatch(message -> message.contains("waiting for its asynchronous completion")),
          60000,
          "the adapter to fail the job of the overdue task");

      final var reported = messagesOf(coreMessages)
          .stream()
          .filter(message -> message.contains("waiting for its asynchronous completion"))
          .toList();
      assertEquals(1, reported.size(), "the core reports an open task once, not once per renewal");
      assertTrue(
          reported.getFirst().contains("vanillabp.delivery.max-task-age"),
          "the report names the property which decided it");

      final var failed = messagesOf(adapterMessages)
          .stream()
          .filter(message -> message.contains("waiting for its asynchronous completion"))
          .findFirst()
          .orElseThrow();
      assertTrue(failed.contains(String.valueOf(aggregateId)), "the incident names the workflow aggregate");
      assertTrue(failed.contains("asyncTask"), "the incident names the task");

      // the incident replaces the renewal: the job stays put instead of being handed
      // out again, so the handler is not invoked a second time
      Thread.sleep(6000);
      assertEquals(
          1,
          invocations(aggregateId),
          "a job which ended in an incident is not redelivered");

    } finally {
      detach(DeliveryRecords.class, coreMessages);
      detach(Camunda8JobHandler.class, adapterMessages);
    }

  }

  private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appenderOn(
      final Class<?> loggingClass) {

    final var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggingClass)).addAppender(appender);
    return appender;

  }

  private static void detach(
      final Class<?> loggingClass,
      final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {

    ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggingClass)).detachAppender(appender);
    appender.stop();

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
