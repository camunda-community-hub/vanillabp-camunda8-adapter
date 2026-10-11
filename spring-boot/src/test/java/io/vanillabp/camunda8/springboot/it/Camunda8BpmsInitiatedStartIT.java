package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of a workflow which the CLUSTER starts on its own, against a real
 * Camunda 8. A timer start event fires. The start execution listener which VanillaBP
 * injected into the model activates a job. The application's
 * <code>&#64;WorkflowStartedByBpms</code> method builds the workflow aggregate. The job
 * completion writes the aggregate's ID into the instance. That is how the service task
 * after the start event finds the aggregate again.
 * <p>
 * The class is skipped when Docker is unavailable.
 * <p>
 * This class has a cluster of its own. The other tests of this module share one. The timer
 * of this model is <code>R1/PT1S</code>. It fires ONCE, a second after the model was
 * deployed. A cluster which already holds that model creates no new timer for the next
 * deployment of it. On the shared cluster, the start would have happened in whichever class
 * deployed first. Then this test would wait for a start it had already missed. So it needs a
 * cluster which has never seen its model.
 * <p>
 * The test configuration gives every class a DATABASE of its own
 * (<code>spring.datasource.generate-unique-name</code>). VanillaBP remembers a delivery by
 * the job key. With a shared database, the records of an earlier class answered this class'
 * task with "processed before". So the handler never ran, while the workflow completed.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
// closed when the class is done. This context has a cluster of its own. Spring would keep
// the context until the JVM exits. And a context which outlives its cluster keeps its job
// workers polling an address nobody answers
@DirtiesContext
public class Camunda8BpmsInitiatedStartIT {

  @Container
  static final GenericContainer<?> CAMUNDA = ClusterUnderTest.cluster("timer-start");

  @DynamicPropertySource
  static void theAddressesOfTheClusterOfThisClass(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.adapters.c8.rest-address", () -> ClusterUnderTest.restAddress(CAMUNDA));
    registry.add("vanillabp.adapters.c8.grpc-address", () -> ClusterUnderTest.grpcAddress(CAMUNDA));

  }

  @Autowired
  private TimerStartDockerAggregateRepository repository;

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    // 60 seconds were not enough in a full build. The class alone needs about 45 of them
    // (cluster start, the timer's cycle, the job worker's poll). So a loaded machine ran
    // out of time while nothing was wrong
    final var deadline = System.currentTimeMillis() + 180_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(200);
    }

  }

  @Test
  @DisplayName("a timer start event creates the workflow aggregate and the following task finds it")
  public void timerStartCreatesTheAggregate() throws Exception {

    // the timer fires a second after the deployment. The start execution listener holds
    // the instance back until the application's method has built the aggregate
    awaitUntil(() -> !repository.findAll().isEmpty(), "the timer to fire and the aggregate to be created");

    final var aggregates = repository.findAll();
    assertEquals(1, aggregates.size(), "one workflow, one aggregate");
    final var aggregate = aggregates.getFirst();

    // the application named the workflow, and nobody else did. The process instance key
    // of the cluster plays no part in it any more
    assertNotNull(aggregate.getId());
    assertTrue(
        aggregate.getId().startsWith("timer-"),
        "the aggregate carries the name the application gave it: "
            + aggregate.getId());
    assertEquals("TIMER", aggregate.getStartedBy(), "the trigger says which start event fired");

    // the service task after the start event ran against exactly that aggregate. This
    // proves that the listener completion wrote the aggregate-ID variable
    awaitUntil(
        () -> "recordTimerStart".equals(
            repository
                .findById(aggregate.getId())
                .map(TimerStartDockerAggregate::getProcessedBy)
                .orElse(null)),
        "the task following the timer start event to be processed");

    // The cluster reports the end of the workflow, and the application's
    // method ran against the aggregate
    awaitUntil(
        () -> "COMPLETED".equals(
            repository
                .findById(aggregate.getId())
                .map(TimerStartDockerAggregate::getEndedAs)
                .orElse(null)),
        "the end of the workflow to be reported");

  }

}
