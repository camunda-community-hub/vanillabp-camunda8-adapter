package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The virtual-thread execution model end to end against a real cluster. A
 * <code>@WorkflowTask</code> method really runs on a virtual thread. And a blocking handler
 * does not delay the job of another worker there either.
 * <p>
 * The bound of the executor has a unit test ({@code Camunda8ExecutorTest}). The builder
 * methods which carry it to the client are asserted per release line
 * ({@code Camunda8JobExecutorsTest}). Only a cluster can show that a handler of a booted
 * application really ends up on one of those threads.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8VirtualThreadsIT extends SpringBootTestOnTheSharedCluster {

  @DynamicPropertySource
  static void camunda8Properties(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.adapters.c8.worker-threads", () -> "virtual");
    registry.add("vanillabp.adapters.c8.worker-threads-bound", () -> "8");

  }

  @Autowired
  private WorkerThreadsDockerWorkflowService blockingWorkflowService;

  @Autowired
  private QuickDockerWorkflowService quickWorkflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @BeforeEach
  public void resetObservations() {

    WorkerThreadsDockerWorkflowService.reset();

  }

  @AfterEach
  public void resetObservationsForWhoeverComesNext() {

    WorkerThreadsDockerWorkflowService.reset();

  }

  @Test
  @DisplayName("the configured bound reaches the executor of the booted application")
  public void theConfiguredBoundReachesTheExecutor() {

    final var factory = clientFactoryRegistry.getFactory("c8");

    assertNotNull(factory.getExecutor(), "the adapter supplies the executor itself");
    assertEquals(8, factory.getExecutor().getBound());

  }

  @Test
  @DisplayName("a handler runs on a virtual thread, and blocking one does not delay another worker")
  public void handlersRunOnVirtualThreads() throws Exception {

    final var blocked = transactionTemplate
        .execute(status -> blockingWorkflowService.startBlocking().getId());
    assertNotNull(blocked);

    assertTrue(
        WorkerThreadsDockerWorkflowService.BLOCKING_ENTERED
            .await(30, TimeUnit.SECONDS),
        "the blocking handler was delivered");

    final var quick = transactionTemplate
        .execute(status -> quickWorkflowService.startWorkflow().getId());
    assertNotNull(quick);

    // the long wait is only a safety margin against a job which never came. The flag below
    // says that the two really ran at the same time, and the quick handler itself reads it.
    // With a wait as long as the block, the wait would have to prove that instead. And a
    // machine which runs several builds stops this JVM for seconds at a time
    assertTrue(
        WorkerThreadsDockerWorkflowService.QUICK_SERVED
            .await(30, TimeUnit.SECONDS),
        "the other worker's job was served at all");
    assertTrue(WorkerThreadsDockerWorkflowService.QUICK_SERVED_WHILE_BLOCKED.get(),
        "the two really ran at the same time");
    assertTrue(WorkerThreadsDockerWorkflowService.QUICK_SERVED_ON_VIRTUAL_THREAD.get(),
        "the handler ran on a virtual thread");

  }

}
