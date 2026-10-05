package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which identifiers a version the cluster still HOLDS declares - the names a workflow
 * module deployed years ago, which no index of the cluster knows and which only that
 * version's model still carries.
 * <p>
 * The model comes back carrying the names the deployment of the day wrote into it, so the
 * prefix is stripped off again before the core sees them: the core composes the scoped forms
 * itself. A job type of such a version is the one of these names which is live rather than
 * dormant, because the workflows still running on that version produce jobs under it.
 * <p>
 * The cluster is played by a definition search and an XML request, and the number of XML
 * requests is part of what is measured: the model of the version in turn is read once,
 * however many questions are put about it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8IdentifiersOfHeldVersionsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  /**
   * The cluster the last question was put to - it counts how often a model was fetched.
   */
  private AClusterHolding cluster;

  /**
   * A model as the cluster runs it: every name carries the prefix the deployment wrote into
   * it, the job type carries the process id as well.
   */
  private static String heldModel(
      final String prefixedNames) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        %s
          <bpmn:process id="loan-approval__LoanApproval" isExecutable="true">
            <bpmn:serviceTask id="Activity_Approve">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="loan-approval__LoanApproval__approveTheOldWay" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(prefixedNames);

  }

  private static final String NAMES_OF_THE_OLD_MODEL = """
        <bpmn:message id="Msg_1" name="loan-approval__PaymentConfirmed" />
        <bpmn:signal id="Sig_1" name="loan-approval__ApprovalWithdrawn" />
      """;

  @Test
  @DisplayName("The names of a held version are read off its model, stripped of the prefix")
  public void theNamesOfAHeldVersionAreRead() {

    final var catalog = aClusterHolding(Map.of(1, heldModel(NAMES_OF_THE_OLD_MODEL)));

    final var declared = List.copyOf(catalog.identifiersOfVersion(MODULE, PROCESS, "1"));

    assertTrue(
        declared.contains(new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "PaymentConfirmed", null)),
        () -> "the message name of that version, as the application knew it: "
            + declared);
    assertTrue(
        declared.contains(new ModelIdentifier(ScopedIdentifierKind.SIGNAL_NAME, "ApprovalWithdrawn", null)),
        () -> "and its signal name: "
            + declared);
    assertTrue(
        declared
            .contains(new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "approveTheOldWay", PROCESS)),
        () -> "the job type of that version, with the process it belongs to - a worker subscribes to "
            + "it while workflows of that version are still running: "
            + declared);

  }

  @Test
  @DisplayName("A held version declaring none of those names answers an empty collection")
  public void aVersionWithoutSuchNamesAnswersEmpty() {

    final var catalog = aClusterHolding(Map.of(1, heldModel("")));

    assertEquals(
        List.of(new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "approveTheOldWay", PROCESS)),
        List.copyOf(catalog.identifiersOfVersion(MODULE, PROCESS, "1")),
        "the model was read and declares nothing but its job type, which is not the same as an "
            + "adapter which cannot say");

  }

  @Test
  @DisplayName("A version the cluster no longer holds is one this adapter cannot say anything about")
  public void aVersionTheClusterDoesNotHoldIsNotAnswered() {

    final var catalog = aClusterHolding(Map.of(1, heldModel(NAMES_OF_THE_OLD_MODEL)));

    assertNull(
        catalog.identifiersOfVersion(MODULE, PROCESS, "2"),
        "no model was read, so nothing is claimed about the names of that version");

  }

  @Test
  @DisplayName("Both model questions about one version share the one fetch of its model")
  public void bothQuestionsAboutAVersionShareOneFetch() {

    final var catalog = aClusterHolding(Map.of(1, heldModel(NAMES_OF_THE_OLD_MODEL)));

    catalog.identifiersOfVersion(MODULE, PROCESS, "1");
    catalog.tasksOfVersion(MODULE, PROCESS, "1");

    assertEquals(
        1,
        cluster.xmlRequests().size(),
        () -> "fetching the XML twice for one version is what the model in turn is held for: "
            + cluster.xmlRequests());

  }

  /**
   * The catalog of a cluster holding the given versions - the boundary to the migration SPI,
   * with the fetches of the models counted by {@link AClusterHolding}.
   */
  private ProcessVersionCatalog aClusterHolding(
      final Map<Integer, String> modelsPerVersion) {

    cluster = AClusterHolding.theseModels(modelsPerVersion);
    return cluster.catalogOf(MODULE, PROCESS);

  }

}
