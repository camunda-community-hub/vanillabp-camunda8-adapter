package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What <code>&#64;WorkflowTask(version = ...)</code> means on Camunda 8, with TWO deployed
 * versions of one process. The method which specifies version 1 serves the workflow which
 * was started while only version 1 exists. A second, TAGGED version is deployed. The method
 * which names that tag serves a workflow started after that.
 * <p>
 * The version TAG is read by searching the cluster. A job carries the NUMBER and never the
 * version tag. Only the cluster can answer which version carries which tag. The numeric
 * half of the feature needs no search at all.
 * <p>
 * The second version is deployed while the application runs, the way another node of a
 * rolling deployment deploys it. So this also tests the on-demand lookup of a version which
 * this application never deployed itself. Its identifiers carry the workflow module's prefix
 * by hand. The test deploys the model directly through the client and so bypasses
 * VanillaBP's pipeline (the adapter runs with name-clash-avoidance 'use-prefix' here).
 * <p>
 * An in-memory database of its own keeps this test apart from the other Camunda 8 ITs. With
 * an outbox they SHARE, another class's application could start this test's workflow. On
 * the cluster which all of them use, that workflow would be served like any other.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = {
        "spring.config.name=camunda8-it", "spring.datasource.url=jdbc:h2:mem:c8-versions-it;DB_CLOSE_DELAY=-1"
    })
public class Camunda8ProcessVersionIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private VersionedDockerWorkflowService workflowService;

  @Autowired
  private VersionedDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    // long on purpose. The cluster exports to Elasticsearch before the query API can
    // answer, and that pipeline is the slowest part of the test
    final var deadline = System.currentTimeMillis() + 240_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(500);
    }

  }

  private void awaitServedBy(
      final Long aggregateId,
      final String expected,
      final String description) throws InterruptedException {

    awaitUntil(
        () -> expected.equals(
            repository
                .findById(aggregateId)
                .map(VersionedDockerAggregate::getServedBy)
                .orElse(null)),
        description);

  }

  @Test
  @DisplayName("the version of the deployed process definition decides which method serves the task")
  public void theVersionDecidesWhichMethodRuns() throws Exception {

    // the application deployed version 1 while booting. A version made of numbers is
    // compared to what the job carries, so nothing is asked of the cluster
    final var firstId = transactionTemplate.execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(firstId);
    awaitServedBy(firstId, "firstVersion", "the method serving version 1 to run");

    client()
        .newDeployResourceCommand()
        .addResourceFromClasspath("versioned/versioned-process-v2.bpmn")
        .send()
        .join();

    // an exporter feeds the query API, so the tagged version arrives there a moment later.
    // And a job of a version which no method serves would use up its retries
    awaitUntil(
        () -> client()
            .newProcessDefinitionSearchRequest()
            .filter(filter -> filter.processDefinitionId("test-app__VersionedProcess"))
            .send()
            .join()
            .items()
            .stream()
            .anyMatch(definition -> "release-2".equals(definition.getVersionTag())),
        "the query API to know the tagged version");

    final var secondId = transactionTemplate.execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(secondId);
    awaitServedBy(
        secondId,
        "taggedVersion",
        "the method naming the version tag of version 2 to run - the version this "
            + "application never deployed itself");

  }

}
