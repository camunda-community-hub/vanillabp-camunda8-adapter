package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of a decision table deployed with its workflow module: the boot sends
 * the module's DMN file to the cluster together with its BPMN files, the business rule
 * task of the process has the cluster evaluate it, and the task after it receives what
 * the decision produced.
 * <p>
 * Nothing here queries what was deployed: the proof is that the workflow reaches the
 * second task at all and carries the value one rule of the table produced.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// the order is part of the test: a broadcast reaches EVERY workflow of the module which
// waits at that moment, and the broadcast test repeats its signal until both of its
// instances continued. Whatever is still in flight from that loop would continue the
// instance of the rollback test as well, which is what made it fail in the GitHub build
// ('expected <null> but was <recordSignal>'). The rollback test therefore runs FIRST, on a
// cluster nobody signalled yet.
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8DecisionTableIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private DecisionDockerWorkflowService workflowService;

  @Autowired
  private DecisionDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("both rules of the module's decision table decide a workflow of that module")
  public void theDecisionOfTheModuleDecides() throws Exception {

    final var approved = transactionTemplate
        .execute(status -> workflowService.startWorkflow(true).getId());
    final var denied = transactionTemplate
        .execute(status -> workflowService.startWorkflow(false).getId());
    assertNotNull(approved);
    assertNotNull(denied);

    awaitRating(approved, "GOOD");
    awaitRating(denied, "POOR");

  }

  /**
   * Waits until the workflow reached the task behind the business rule task and wrote
   * what the decision produced into its aggregate.
   *
   * @param aggregateId The workflow's aggregate
   * @param expected What the rule of the table produces for it
   */
  private void awaitRating(
      final Long aggregateId,
      final String expected) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 150_000;
    while (!expected.equals(ratingOf(aggregateId))) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "the workflow of aggregate '%s' never carried the rating '%s' (it carries '%s')"
                .formatted(aggregateId, expected, ratingOf(aggregateId)));
      }
      Thread.sleep(1000);
    }

  }

  private String ratingOf(
      final Long aggregateId) {

    return repository
        .findById(aggregateId)
        .map(DecisionDockerAggregate::getRating)
        .orElse(null);

  }

}
