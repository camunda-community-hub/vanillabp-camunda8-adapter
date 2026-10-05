package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which rounds a task of a version the CLUSTER STILL HOLDS iterates in without being handed
 * their value. A handler reads the item of an iteration out of the variable the model names
 * in <code>inputElement</code>, and an element naming none hands nothing over, so a method
 * serving that version receives <code>null</code> and nothing says why.
 * <p>
 * Reading the model is this adapter's job and judging the methods is the core's, so what is
 * measured here is the shape the adapter hands over at the boundary to the migration SPI:
 * {@link BpmnTaskSpec#multiInstanceElementsWithoutAnItem()} of a task of a held version.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ItemsOfHeldVersionsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String TASK = "Activity_Check";

  /**
   * A model as the cluster runs it, with the multi-instance shape the test is about. The
   * names carry the prefix the deployment wrote into them.
   */
  private static String heldModel(
      final String multiInstanceShape) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="loan-approval__LoanApproval" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(multiInstanceShape);

  }

  private static final String AN_ELEMENT_NAMING_ITS_ITEM = """
          <bpmn:serviceTask id="Activity_Check">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="loan-approval__LoanApproval__checkCredit" />
            </bpmn:extensionElements>
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=applications" inputElement="application" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
          </bpmn:serviceTask>
      """;

  private static final String AN_ELEMENT_NAMING_NO_ITEM = """
          <bpmn:serviceTask id="Activity_Check">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="loan-approval__LoanApproval__checkCredit" />
            </bpmn:extensionElements>
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=applications" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
          </bpmn:serviceTask>
      """;

  private static final String LISTENER_ELEMENT = "Event_RoundDone";

  private static final String A_LISTENER_IN_A_SUBPROCESS_NAMING_NO_ITEM = """
          <bpmn:subProcess id="SubProcess_Applications">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=applications" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:endEvent id="Event_RoundDone">
              <bpmn:extensionElements>
                <zeebe:executionListeners>
                  <zeebe:executionListener eventType="end" type="loan-approval__LoanApproval__auditTheRound" />
                </zeebe:executionListeners>
              </bpmn:extensionElements>
            </bpmn:endEvent>
          </bpmn:subProcess>
      """;

  private static final String A_SUBPROCESS_NAMING_NO_ITEM = """
          <bpmn:subProcess id="SubProcess_Applications">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=applications" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:serviceTask id="Activity_Check">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="loan-approval__LoanApproval__checkCredit" />
              </bpmn:extensionElements>
              <bpmn:multiInstanceLoopCharacteristics>
                <bpmn:extensionElements>
                  <zeebe:loopCharacteristics inputCollection="=documents" inputElement="document" />
                </bpmn:extensionElements>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:serviceTask>
          </bpmn:subProcess>
      """;

  @Test
  @DisplayName("A held version whose element names its item answers an empty list")
  public void aVersionNamingItsItem() {

    assertEquals(List.of(), itemsNeverNamedBy(AN_ELEMENT_NAMING_ITS_ITEM));

  }

  @Test
  @DisplayName("A held version whose element names no item names that element")
  public void aVersionNamingNoItem() {

    assertEquals(List.of(TASK), itemsNeverNamedBy(AN_ELEMENT_NAMING_NO_ITEM));

  }

  @Test
  @DisplayName("Only the elements of the task's own chain are named, outermost first")
  public void onlyTheChainOfTheTask() {

    // the subprocess hands no item over and the task inside it does, so the one round
    // without a value is the outer one
    assertEquals(List.of("SubProcess_Applications"), itemsNeverNamedBy(A_SUBPROCESS_NAMING_NO_ITEM));

  }

  @Test
  @DisplayName("A modelled listener of a held version names the rounds around it too")
  public void aListenerOfAHeldVersion() {

    // a listener method reads its item out of the same iteration a task's method does, so
    // leaving the chain off a listener would hide the finding for that half of the model
    final var cluster = AClusterHolding
        .theseModels(Map.of(1, heldModel(A_LISTENER_IN_A_SUBPROCESS_NAMING_NO_ITEM)))
        .servingModelledListeners();

    assertEquals(
        List.of("SubProcess_Applications"),
        cluster
            .catalogOf(MODULE, PROCESS)
            .tasksOfVersion(MODULE, PROCESS, "1")
            .stream()
            .filter(task -> LISTENER_ELEMENT.equals(task.activityId()))
            .map(BpmnTaskSpec::multiInstanceElementsWithoutAnItem)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the listener of the held version was not read at all")));

  }

  @Test
  @DisplayName("A version the cluster no longer holds is not answered at all")
  public void aVersionTheClusterDoesNotHold() {

    final var cluster = AClusterHolding.theseModels(Map.of(1, heldModel(AN_ELEMENT_NAMING_NO_ITEM)));

    assertNull(
        cluster.catalogOf(MODULE, PROCESS).tasksOfVersion(MODULE, PROCESS, "2"),
        "no model was read, so nothing is claimed about the items of that version");

  }

  /**
   * What the catalog answers about the one task of version 1 of the given model.
   */
  private static List<String> itemsNeverNamedBy(
      final String multiInstanceShape) {

    final var cluster = AClusterHolding.theseModels(Map.of(1, heldModel(multiInstanceShape)));
    return cluster
        .catalogOf(MODULE, PROCESS)
        .tasksOfVersion(MODULE, PROCESS, "1")
        .stream()
        .filter(task -> TASK.equals(task.activityId()))
        .map(BpmnTaskSpec::multiInstanceElementsWithoutAnItem)
        .findFirst()
        .orElseThrow(() -> new AssertionError("the task of the held version was not read at all"));

  }

}
