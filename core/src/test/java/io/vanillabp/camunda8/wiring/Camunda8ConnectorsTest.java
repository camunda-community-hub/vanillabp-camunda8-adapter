package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeFormDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which elements of a model this application stops serving once connectors are allowed, and
 * which it keeps serving whatever the model carries.
 * <p>
 * Every case is asserted together with the element next to it which must NOT be affected:
 * the property is an escape hatch for single elements, and one which reached further than
 * the marker would silently unwire tasks the application does serve.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ConnectorsTest {

  private static final String MODULE = "test-module";

  private static BpmnModelInstance model(
      final String processContent) {

    return model("zeebe", "http://camunda.org/schema/zeebe/1.0", processContent);

  }

  /**
   * The same model with a freely chosen prefix and namespace for the zeebe extensions - a
   * diagram written by a tool which aliases the namespace looks exactly like this.
   */
  private static BpmnModelInstance model(
      final String prefix,
      final String namespace,
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:%s="%s" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="TestProcess" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(prefix, namespace, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * A connector service task next to an ordinary one, which is the shape every case below
   * needs: the ordinary task is what proves the rule did not reach too far.
   */
  private static final String A_CONNECTOR_AND_AN_ORDINARY_TASK = """
          <bpmn:serviceTask id="Activity_Fetch" zeebe:modelerTemplate="io.camunda.connectors.HttpJson.v2">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="io.camunda:http-json:1" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
          <bpmn:serviceTask id="Activity_Approve">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approve" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
      """;

  private static List<String> wiredActivityIdsOf(
      final BpmnModelInstance model,
      final boolean connectorsAreAllowed) {

    return Camunda8TaskWiring
        .tasksOf(model, "TestProcess", connectorsAreAllowed)
        .stream()
        .map(Camunda8TaskWiring.Camunda8TaskToWire::activityId)
        .toList();

  }

  @Test
  @DisplayName("A connector task is passed over where connectors are allowed, its neighbour is not")
  public void aConnectorTaskIsPassedOver() {

    final var model = model(A_CONNECTOR_AND_AN_ORDINARY_TASK);

    assertEquals(
        List.of("Activity_Approve"),
        wiredActivityIdsOf(model, true),
        "the connector's job type belongs to the connector runtime, so nothing here asks for it");

  }

  @Test
  @DisplayName("Both tasks are collected where connectors are not allowed")
  public void withoutThePropertyBothTasksAreCollected() {

    final var model = model(A_CONNECTOR_AND_AN_ORDINARY_TASK);

    assertEquals(
        List.of("Activity_Fetch", "Activity_Approve"),
        wiredActivityIdsOf(model, false),
        "which is what this adapter has always done, and what stays without the property");

  }

  @Test
  @DisplayName("An element template written with an aliased zeebe prefix is recognised")
  public void anAliasedNamespaceIsRecognised() {

    final var model = model("z", "http://camunda.org/schema/zeebe/1.0", """
            <bpmn:serviceTask id="Activity_Fetch" z:modelerTemplate="io.camunda.connectors.HttpJson.v2">
              <bpmn:extensionElements>
                <z:taskDefinition type="io.camunda:http-json:1" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
        """);

    assertEquals(
        List.of(),
        wiredActivityIdsOf(model, true),
        "the prefix is the modeller's choice, the namespace is what identifies the attribute");

  }

  @Test
  @DisplayName("An element carrying the marker but no job type stays where it was")
  public void aMarkerWithoutAJobTypeChangesNothing() {

    final var model = model("""
            <bpmn:serviceTask id="Activity_Fetch" zeebe:modelerTemplate="acme.PlainTask.v1" />
        """);

    assertEquals(
        List.of("Activity_Fetch"),
        wiredActivityIdsOf(model, true),
        "nothing subscribes to a job type it does not name, so this is a model to be reported");

  }

  @Test
  @DisplayName("A user task built from an element template keeps its wiring")
  public void aUserTaskBuiltFromATemplateStaysWired() {

    final var model = model("""
            <bpmn:userTask id="Activity_Approve" zeebe:modelerTemplate="acme.ApprovalForm.v1">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="approvalForm" />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """);

    final var userTasks = Camunda8TaskWiring
        .userTasksOf(model, "TestProcess", MODULE, "approval.bpmn");

    assertEquals(1, userTasks.size(), "a template on a user task presets a form, it names no runtime");
    assertEquals("approvalForm", userTasks.getFirst().externalFormReference());

  }

  private static final Camunda8AllowConnectorsResolver CONNECTORS_ARE_ALLOWED = (
      workflowModuleId,
      bpmnProcessId) -> new Camunda8AllowConnectorsResolver.Setting(
          true, "vanillabp.adapters.c8.allow-connectors");

  private static List<String> jobTypesOf(
      final BpmnModelInstance model) {

    return model
        .getModelElementsByType(ZeebeTaskDefinition.class)
        .stream()
        .map(ZeebeTaskDefinition::getType)
        .toList();

  }

  @Test
  @DisplayName("Prefixing leaves a connector's job type alone and still prefixes the ordinary one")
  public void prefixingLeavesAConnectorAlone() {

    final var model = model(A_CONNECTOR_AND_AN_ORDINARY_TASK);

    Camunda8Scoping
        .apply(model, MODULE, "c8", TestScoping.of(NameClashAvoidance.USE_PREFIX), CONNECTORS_ARE_ALLOWED, null);

    assertEquals(
        List.of("io.camunda:http-json:1", "test-module__TestProcess__approve"),
        jobTypesOf(model),
        "prefixing a connector's job type would rename something this application does not own");

  }

  @Test
  @DisplayName("Prefixing rewrites a connector's job type where connectors are not allowed")
  public void prefixingRewritesEverythingWithoutTheProperty() {

    final var model = model(A_CONNECTOR_AND_AN_ORDINARY_TASK);

    Camunda8Scoping
        .apply(model, MODULE, "c8", TestScoping.of(NameClashAvoidance.USE_PREFIX), null, null);

    assertEquals(
        List.of("test-module__TestProcess__io.camunda:http-json:1", "test-module__TestProcess__approve"),
        jobTypesOf(model),
        "without the property the element is an ordinary task of this module and scoped like one");

  }

  @Test
  @DisplayName("An ad-hoc subprocess built from an element template keeps its unprefixed job type")
  public void anAdHocSubProcessKeepsItsJobType() {

    final var model = model(
        """
                <bpmn:adHocSubProcess id="Activity_Agent" zeebe:modelerTemplate="io.camunda.connectors.agenticai.aiagent.subprocess.v1">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="io.camunda.agenticai:aiagent-job-worker:1" />
                  </bpmn:extensionElements>
                </bpmn:adHocSubProcess>
                <bpmn:serviceTask id="Activity_Approve">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="approve" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
            """);

    Camunda8Scoping
        .apply(model, MODULE, "c8", TestScoping.of(NameClashAvoidance.USE_PREFIX), CONNECTORS_ARE_ALLOWED, null);

    assertEquals(
        List.of("io.camunda.agenticai:aiagent-job-worker:1", "test-module__TestProcess__approve"),
        jobTypesOf(model),
        "an ad-hoc subprocess is none of the elements the wiring collects, and scoping reaches it");

  }

  @Test
  @DisplayName("The form reference of a connector element is left alone, that of a user task is not")
  public void theFormReferenceFollowsTheElement() {

    final var model = model("""
            <bpmn:serviceTask id="Activity_Fetch" zeebe:modelerTemplate="io.camunda.connectors.HttpJson.v2">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="io.camunda:http-json:1" />
                <zeebe:formDefinition externalReference="connectorForm" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
            <bpmn:userTask id="Activity_Approve" zeebe:modelerTemplate="acme.ApprovalForm.v1">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="approvalForm" />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """);

    Camunda8Scoping
        .apply(model, MODULE, "c8", TestScoping.of(NameClashAvoidance.USE_PREFIX), CONNECTORS_ARE_ALLOWED, null);

    final var references = model
        .getModelElementsByType(ZeebeFormDefinition.class)
        .stream()
        .map(ZeebeFormDefinition::getExternalReference)
        .toList();
    assertEquals(
        List.of("connectorForm", "test-module__TestProcess__approvalForm"),
        references,
        "the user task is served by this application, so its listener job type is scoped as always");

  }

  @Test
  @DisplayName("The elements another runtime serves are named with process, element and template")
  public void theElementsAreNamedForTheReport() {

    final var found = Camunda8Connectors
        .elementsServedByAnotherRuntime(model(A_CONNECTOR_AND_AN_ORDINARY_TASK), "TestProcess");

    assertEquals(1, found.size());
    final var element = found.getFirst();
    assertEquals("TestProcess", element.bpmnProcessId());
    assertEquals("Activity_Fetch", element.elementId());
    assertEquals("io.camunda.connectors.HttpJson.v2", element.elementTemplate());
    assertTrue(element.describe().contains("io.camunda.connectors.HttpJson.v2"));

  }

  @Test
  @DisplayName("A key set at task level is reported and the boot goes on")
  public void aKeyAtTaskLevelIsReported() {

    final var reported = new java.util.ArrayList<String>();

    Camunda8Connectors.reportKeysSetAtTaskLevel("c8", List.of(), reported::add);
    assertTrue(reported.isEmpty(), "nothing to say where nobody set the key there");

    Camunda8Connectors
        .reportKeysSetAtTaskLevel(
            "c8",
            List.of(
                "vanillabp.workflow-modules.test-module.workflows.TestProcess.tasks.approve.adapters.c8.allow-connectors"),
            reported::add);

    assertEquals(1, reported.size());
    assertTrue(
        reported.getFirst().contains("tasks.approve.adapters.c8.allow-connectors"),
        () -> "the key the reader has to find in their configuration: "
            + reported.getFirst());
    assertTrue(
        reported.getFirst().contains("vanillabp.workflow-modules.<m>.adapters.c8.allow-connectors"),
        () -> "and the levels which do resolve it: "
            + reported.getFirst());
    assertFalse(
        reported.getFirst().contains(".tasks.<"),
        () -> "the task level is not among them: "
            + reported.getFirst());

  }

}
