package io.vanillabp.camunda8.springboot.paramtypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.Incident;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a {@code @TaskParam} of a Camunda 8 job receives, against a real cluster. And what
 * happens if the value the cluster holds does not fit the type the handler declared.
 * <p>
 * The conversion itself belongs to the platform. This adapter hands over what
 * {@code job.getVariablesAsMap()} holds and converts nothing. What is special to Camunda 8 is
 * WHICH value arrives. The cluster keeps its variables as JSON. So a decimal comes back as a
 * Double and a whole number as a Long, whatever an aggregate shared. The interesting pairs
 * here are therefore not the pairs of another BPMS, and they are worth a test of their own.
 * <p>
 * The model has nine branches of one parallel gateway. Five of them reach their handler, and
 * four fail before it. The branches fork because a job which can never succeed leaves an
 * incident behind and stays where it is. That must not stop the branch next to it. The test
 * reads the refusal from the incident which the cluster raises once the job's retries are
 * gone.
 * <p>
 * A refused job uses up its retries first, because the job does not know that this failure
 * can never succeed. That is why this scenario configures {@code retry-backoff: PT1S} for its
 * process. The conversion does not depend on it. Without it, the four incidents would arrive
 * half a minute later.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = ParamTypesTestApplication.class,
    properties = "spring.config.name=camunda8-param-types-it")
public class Camunda8ParamTypesIT extends SpringBootTestOnTheSharedCluster {

  /**
   * The branches whose handler is entered. Here the element id of a branch, its task
   * definition and the name of its handler are all the same word.
   */
  private static final List<String> BRANCHES_WHICH_RUN = List
      .of(
          "totalToDouble",
          "totalToBigDecimal",
          "rateToDouble",
          "countToLong",
          "hugeToLong");

  /**
   * The branches whose conversion is refused before the handler is entered.
   */
  private static final List<String> BRANCHES_WHICH_FAIL = List
      .of(
          "totalToInt",
          "countToInt",
          "hugeToDouble",
          "totalTextToInt");

  @Autowired
  private ParamTypesWorkflowService workflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Test
  @DisplayName("What a @TaskParam receives, and what a value which does not fit the declared type does")
  public void whatATaskParamReceivesAndWhatAValueWhichDoesNotFitDoes() throws Exception {

    transactionTemplate.execute(status -> workflowService.startWorkflow());

    awaitUntil(this::everyBranchSettled);

    theValuesWhichArrive();
    theValuesWhichAreRefused();

  }

  /**
   * @return Whether every branch of the model has finished: the five which run have entered
   *         their handler, and the four which do not have left an incident behind
   */
  private boolean everyBranchSettled() {

    final var entered = workflowService
        .received()
        .keySet();
    final var failed = incidentsByElement()
        .keySet();
    return entered.containsAll(BRANCHES_WHICH_RUN) && failed.containsAll(BRANCHES_WHICH_FAIL);

  }

  /**
   * The five pairs which are served, and the number each of them is served with.
   */
  private void theValuesWhichArrive() {

    final var received = workflowService.received();

    // the aggregate shared 120.50, and the handler reads 120.5. The scale is gone before
    // this adapter ever sees the value. The broker keeps its variables as MessagePack, and
    // MessagePack has no decimal type
    assertEquals(
        Double.valueOf(120.5d),
        received.get("totalToDouble"),
        "a decimal comes back as a Double, without its scale");

    // the cluster never answers with a BigDecimal, so this one IS converted. It is converted
    // from the value the cluster holds, which no longer has the scale
    assertEquals(
        new BigDecimal("120.5"),
        received.get("totalToBigDecimal"),
        "a BigDecimal parameter is served from the Double the cluster returns");

    // 0.1f widened to a double would be 0.10000000149011612. It is not, because the
    // cluster holds the TEXT 0.1 and reads it back as a Double of 0.1
    assertEquals(
        Double.valueOf(0.1d),
        received.get("rateToDouble"),
        "a float attribute comes back as the Double of the number which was written");

    // version 1 accepted this pair by plain widening, and it has to keep working
    assertEquals(
        Long.valueOf(3000000000L),
        received.get("countToLong"),
        "a long above Integer.MAX_VALUE arrives as it was shared");

    assertEquals(
        Long.valueOf(9007199254740993L),
        received.get("hugeToLong"),
        "a whole number above 2^53 arrives exactly, a long being able to hold it");

  }

  /**
   * The four pairs which are refused, and the message of each refusal. Each of them used to
   * arrive as a number which nobody wrote.
   */
  private void theValuesWhichAreRefused() {

    final var incidents = incidentsByElement();

    // the handler used to get 120 instead of 120.5, without any warning
    assertRefusal(
        incidents,
        "totalToInt",
        "does not fit the parameter's type 'int'",
        "which would hold '120'");

    // and -1294967296 instead of 3000000000, which reads like a deliberate value
    assertRefusal(
        incidents,
        "countToInt",
        "The value '3000000000'",
        "which would hold '-1294967296'");

    // a double cannot hold 9007199254740993. It holds the number below it instead
    assertRefusal(
        incidents,
        "hugeToDouble",
        "does not fit the parameter's type 'java.lang.Double'",
        "which would hold '9.007199254740992E15'");

    // the text of a number is a number as well. This pair used to end in a bare
    // NumberFormatException, which named neither the parameter nor the method
    assertRefusal(
        incidents,
        "totalTextToInt",
        "does not fit the parameter's type 'int'",
        "which would hold '120'");
    assertFalse(
        incidents.get("totalTextToInt").getErrorMessage().contains("NumberFormatException"),
        "the text of a number is refused with the guiding message rather than with a "
            + "NumberFormatException: "
            + incidents.get("totalTextToInt").getErrorMessage());

    BRANCHES_WHICH_FAIL
        .forEach(branch -> assertNull(
            workflowService.received().get(branch),
            "the handler of "
                + branch
                + " was entered although its parameter could not be bound"));

  }

  private void assertRefusal(
      final Map<String, Incident> incidents,
      final String branch,
      final String... whatTheMessageSays) {

    final var incident = incidents.get(branch);
    assertNotNull(
        incident,
        "the cluster holds no incident of "
            + branch
            + " any more");
    assertEquals(
        "JOB_NO_RETRIES",
        String.valueOf(incident.getErrorType()),
        "the refusal of "
            + branch
            + " becomes an incident once the job's retries are gone");
    for (final var expected : whatTheMessageSays) {
      assertTrue(
          incident.getErrorMessage().contains(expected),
          "the incident of "
              + branch
              + " says '"
              + expected
              + "', but reads: "
              + incident.getErrorMessage());
    }

  }

  /**
   * The incidents of the cluster, by the element they sit on. This scenario runs one
   * workflow of one model, so every incident the cluster holds belongs to it.
   */
  private Map<String, Incident> incidentsByElement() {

    final var byElement = new HashMap<String, Incident>();
    try {
      client()
          .newIncidentSearchRequest()
          .page(page -> page.limit(100))
          .send()
          .join()
          .items()
          .forEach(incident -> byElement.put(incident.getElementId(), incident));
      return byElement;
    } catch (final Exception e) {
      // the query API is served from secondary storage. A cluster which just started may
      // not have it yet
      return Map.of();
    }

  }

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

  private void awaitUntil(
      final Supplier<Boolean> condition) throws InterruptedException {

    // generous on purpose. A refused job is handed out until its retries are gone. The
    // incident which follows is read from secondary storage, and the exporter fills that
    // storage a moment later
    final var deadline = System.currentTimeMillis() + 180_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(whatIsStillMissing());
      }
      Thread.sleep(500);
    }

  }

  /**
   * Which branches of the model have not finished yet, named one by one. A timeout which only
   * says that something is missing costs a second run to find out which half it was. And the
   * two halves fail for opposite reasons. A handler nobody entered points at the cluster or at
   * the wiring. A refusal which never became an incident points at a conversion which let the
   * value through.
   *
   * @return The message of the timeout
   */
  private String whatIsStillMissing() {

    final var entered = workflowService
        .received()
        .keySet();
    final var withAnIncident = incidentsByElement().keySet();
    return "timed out waiting for every branch to settle. Branches which never entered "
        + "their handler: "
        + BRANCHES_WHICH_RUN
            .stream()
            .filter(branch -> !entered.contains(branch))
            .toList()
        + ". Branches which never left an incident behind: "
        + BRANCHES_WHICH_FAIL
            .stream()
            .filter(branch -> !withAnIncident.contains(branch))
            .toList()
        + ". What the handlers which did run received: "
        + workflowService.received();

  }

}
