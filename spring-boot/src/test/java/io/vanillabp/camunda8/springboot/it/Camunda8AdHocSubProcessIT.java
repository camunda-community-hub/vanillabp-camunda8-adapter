package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An ad-hoc subprocess against a REAL Camunda 8 broker. The activities which the workflow
 * aggregate names are the ones which run. The workflow leaves the element when they are
 * done.
 *
 * <p>
 * Nothing was added to the adapter for this. The wiring does not collect the element. The
 * activities inside it are ordinary tasks. The list of ids reaches the cluster because the
 * workflow aggregate is shared with every command. This test proves that serving the
 * element costs no framework code. Only a test against a cluster can prove it, because the
 * cluster evaluates the expression.
 * </p>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8AdHocSubProcessIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private AdHocDockerWorkflowService workflowService;

  @Autowired
  private AdHocDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(250);
    }

  }

  @Test
  @DisplayName("the two activities the aggregate names run, the third does not, and the element is left")
  public void theActivitiesTheAggregateNamesAreTheOnesWhichRun() throws Exception {

    final var picked = new LinkedHashSet<String>(Set.of("AdHoc_CheckFraud", "AdHoc_CheckIncome"));

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow(picked).getId());
    assertNotNull(aggregateId);

    // the service task AFTER the subprocess proves that the workflow left it. No completion
    // condition is modelled, so the element ends when its activated activities end
    awaitUntil(
        () -> repository
            .findById(aggregateId)
            .map(AdHocDockerAggregate::getSummarized)
            .isPresent(),
        "the workflow to have left the ad-hoc subprocess");

    final var aggregate = repository
        .findById(aggregateId)
        .orElseThrow();

    assertTrue(aggregate.getFraudChecked(), "the first activity the aggregate named");
    assertTrue(aggregate.getIncomeChecked(), "the second activity the aggregate named");
    assertNull(
        aggregate.getCollateralChecked(),
        "the activity nobody picked stays untouched although it is in the model");
    assertEquals(picked, aggregate.getChecksToRun(), "what the workflow was started with");

  }

}
