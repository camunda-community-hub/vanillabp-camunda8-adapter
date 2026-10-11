package io.vanillabp.camunda8.springboot.connectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a model carrying a connector element does on a real cluster while
 * {@code allow-connectors} is on for its process.
 * <p>
 * Be clear about what this can prove and what it cannot. A connector runtime is a Camunda
 * component which this project does not run. So nothing here executes a connector, and the
 * workflow is expected to wait at the element forever. The cluster proves the rest:
 * <ul>
 * <li>the model deploys, and a workflow starts on it,</li>
 * <li>the adapter opened no worker for the connector's job type,</li>
 * <li>the job waiting on the cluster has that job type UNPREFIXED, although this workflow
 * module runs with name-clash avoidance {@code use-prefix}.</li>
 * </ul>
 * <p>
 * The scenario brings its own application, its own configuration file and its own resources
 * location. See {@link ConnectorTestApplication}. The other integration tests of this module
 * must not be made to deploy a process which no {@code @WorkflowTask} method serves.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = ConnectorTestApplication.class,
    properties = "spring.config.name=camunda8-connectors-it")
public class Camunda8ConnectorsIT extends SpringBootTestOnTheSharedCluster {

  /**
   * The job type of the connector element, as the modeller wrote it. The assertion is that the
   * search below finds a job under this name at all. A job type of this workflow module would
   * carry the module and the process, because the module runs under 'use-prefix'.
   */
  private static final String CONNECTOR_JOB_TYPE = "io.camunda:http-json:1";

  @Autowired
  private ConnectorDockerWorkflowService workflowService;

  @Autowired
  private ConnectorDockerAggregateRepository repository;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("The workflow waits at the connector, and its job carries the unprefixed job type")
  public void theWorkflowWaitsAtTheConnector() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    final var elementWaitingForTheJob = awaitTheElementHoldingAJobOfTheConnectorType();
    assertTrue(
        elementWaitingForTheJob.contains("Activity_Connector"),
        () -> "the job waiting on the cluster belongs to the connector element: "
            + elementWaitingForTheJob);

    // the same run proves the other half. The task behind the element was never reached, so
    // nothing of this application served that job
    Thread.sleep(5000);
    final var pastTheConnector = Boolean.TRUE
        .equals(
            transactionTemplate
                .execute(status -> repository.findById(aggregateId).orElseThrow().isPastTheConnector()));
    assertFalse(
        pastTheConnector,
        "no worker of this application may serve a job type belonging to a connector runtime");

  }

  /**
   * Waits until the cluster holds a job of the connector's job type and reports which
   * element it belongs to.
   *
   * @return The element id of that job
   */
  private String awaitTheElementHoldingAJobOfTheConnectorType() throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 150_000;
    while (true) {
      final var found = clientFactoryRegistry
          .getFactory("c8")
          .getClient()
          .newJobSearchRequest()
          .filter(filter -> filter.type(CONNECTOR_JOB_TYPE))
          .send()
          .join()
          .items();
      if (!found.isEmpty()) {
        return found.getFirst().getElementId();
      }
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "the cluster never held a job of type '%s' - under 'use-prefix' a job type of this module "
                .formatted(CONNECTOR_JOB_TYPE)
                + "would carry the module and the process, and a connector's does not");
      }
      Thread.sleep(1000);
    }

  }

}
