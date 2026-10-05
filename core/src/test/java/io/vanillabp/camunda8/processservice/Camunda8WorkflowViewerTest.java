package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowElementType;

/**
 * Unit tests of the viewer/history API's Camunda 8 part against a cluster which answers
 * nothing at all: the degradation which stays. Whatever a search cannot answer, because
 * the cluster is unreachable or because the data is not visible yet, is served from what
 * this application version deployed - and never reported as an error.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8WorkflowViewerTest {

  private static final String PARENT_BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
      xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_Parent" \
      targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="ParentProcess" isExecutable="true">
          <bpmn:startEvent id="TheStart" />
          <bpmn:callActivity id="TheCallActivity">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="SubProcess" propagateAllChildVariables="false" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
          <bpmn:callActivity id="TheDynamicCallActivity">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="=dynamicProcessId" propagateAllChildVariables="false" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String SUB_BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_Sub" \
      targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="SubProcess" isExecutable="true">
          <bpmn:startEvent id="TheSubStart" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A client factory whose cluster is never reachable - every query API call fails,
   * which is exactly the situation the degradation is about.
   */
  private static Camunda8ClientFactory unreachableClientFactory() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:1");
    configuration.setGrpcAddress("http://localhost:1");
    return new Camunda8ClientFactory("c8", configuration);

  }

  private static Camunda8ClientFactory clientFactoryWithDeployedProcesses() {

    final var clientFactory = unreachableClientFactory();
    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                "test-module", "ParentProcess", "111", 3, Bpmn
                    .readModelFromStream(new ByteArrayInputStream(PARENT_BPMN.getBytes(StandardCharsets.UTF_8)))));
    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                "test-module", "SubProcess", "222", 1, Bpmn
                    .readModelFromStream(new ByteArrayInputStream(SUB_BPMN.getBytes(StandardCharsets.UTF_8)))));
    return clientFactory;

  }

  @Test
  @DisplayName("Where the cluster does not answer the definitions of the deployed version are reported, incl. call activities")
  public void definitionsAreServedFromTheDeployedVersion() {

    final var viewer = new Camunda8WorkflowViewer("c8", clientFactoryWithDeployedProcesses(), (
        module,
        process) -> process, module -> null);

    final var definitions = viewer.getProcessDefinitions("test-module", "ParentProcess", "id", "42", null);

    assertEquals(2, definitions.size(), () -> "expected the process and its called process but got: "
        + definitions);
    assertEquals("111", definitions.get(0).id());
    assertEquals("ParentProcess", definitions.get(0).bpmnProcessId());
    assertEquals("3", definitions.get(0).version());
    assertNull(definitions.get(0).usedByElements());
    // the call activity addressing its process by a FEEL expression is skipped -
    // which definition it calls is only known at execution time
    assertEquals("222", definitions.get(1).id());
    assertEquals(List.of("TheCallActivity"), definitions.get(1).usedByElements());

  }

  @Test
  @DisplayName("The BPMN XML is served from the deployed model; an unknown definition answers null")
  public void bpmnXmlIsServedFromTheDeployedModel() throws Exception {

    final var viewer = new Camunda8WorkflowViewer("c8", clientFactoryWithDeployedProcesses(), (
        module,
        process) -> process, module -> null);

    try (var xml = viewer.getBpmnXml("111")) {
      final var deployedXml = new String(xml.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(
          deployedXml.contains("ParentProcess"),
          () -> "the BPMN XML has to contain the process but got: "
              + deployedXml);
    }

    // deployed by a previous application version: only the cluster would know it -
    // unreachable here, so the core turns the null into its guiding exception
    assertNull(viewer.getBpmnXml("999"));
    assertNull(viewer.getBpmnXml("not-a-key"));

  }

  @Test
  @DisplayName("Where the cluster does not answer a history without elements is reported - never an error")
  public void historyDegradesToNoElements() {

    final var viewer = new Camunda8WorkflowViewer("c8", clientFactoryWithDeployedProcesses(), (
        module,
        process) -> process, module -> null);

    final var history = viewer.getWorkflowHistory("test-module", "ParentProcess", "id", "42", null);

    assertEquals("111", history.processDefinitionId());
    assertNull(history.startTime());
    assertNull(history.endTime());
    assertNull(history.elementsHistory(), "the SPI expresses 'no element history' as null");

    // a secondary history context cannot be resolved where the cluster does not
    // answer - the core turns that into its guiding WorkflowNotFoundException
    assertNull(viewer.getWorkflowHistory("test-module", "ParentProcess", "id", "42", "12345"));
    assertEquals(
        List.of(),
        viewer.getProcessDefinitions("test-module", "ParentProcess", "id", "42", "12345"));

  }

  @Test
  @DisplayName("A process never deployed by this application version is unknown to the adapter")
  public void unknownProcessIsReportedAsUnknown() {

    final var viewer = new Camunda8WorkflowViewer("c8", clientFactoryWithDeployedProcesses(), (
        module,
        process) -> process, module -> null);

    assertEquals(List.of(), viewer.getProcessDefinitions("test-module", "OtherProcess", "id", "42", null));
    assertNull(viewer.getWorkflowHistory("test-module", "OtherProcess", "id", "42", null));

  }

  @Test
  @DisplayName("Camunda 8 element instance types map onto the SPI's element types")
  public void elementTypesAreMapped() {

    assertEquals(WorkflowElementType.SERVICE_TASK, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.SERVICE_TASK));
    assertEquals(WorkflowElementType.CALL_ACTIVITY, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.CALL_ACTIVITY));
    assertEquals(WorkflowElementType.MULTI_INSTANCE, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.MULTI_INSTANCE_BODY));
    assertEquals(WorkflowElementType.AD_HOC_SUB_PROCESS, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.AD_HOC_SUB_PROCESS_INNER_INSTANCE));
    assertEquals(WorkflowElementType.PROCESS, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.PROCESS));
    assertEquals(WorkflowElementType.SUB_PROCESS, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.SUB_PROCESS));
    assertEquals(WorkflowElementType.EVENT_SUB_PROCESS, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.EVENT_SUB_PROCESS));
    assertEquals(WorkflowElementType.AD_HOC_SUB_PROCESS, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.AD_HOC_SUB_PROCESS));
    assertEquals(WorkflowElementType.START_EVENT, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.START_EVENT));
    assertEquals(WorkflowElementType.INTERMEDIATE_CATCH_EVENT, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.INTERMEDIATE_CATCH_EVENT));
    assertEquals(WorkflowElementType.INTERMEDIATE_THROW_EVENT, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.INTERMEDIATE_THROW_EVENT));
    assertEquals(WorkflowElementType.BOUNDARY_EVENT, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.BOUNDARY_EVENT));
    assertEquals(WorkflowElementType.END_EVENT, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.END_EVENT));
    assertEquals(WorkflowElementType.RECEIVE_TASK, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.RECEIVE_TASK));
    assertEquals(WorkflowElementType.USER_TASK, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.USER_TASK));
    assertEquals(WorkflowElementType.MANUAL_TASK, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.MANUAL_TASK));
    assertEquals(WorkflowElementType.TASK, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.TASK));
    assertEquals(WorkflowElementType.EXCLUSIVE_GATEWAY, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.EXCLUSIVE_GATEWAY));
    assertEquals(WorkflowElementType.INCLUSIVE_GATEWAY, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.INCLUSIVE_GATEWAY));
    assertEquals(WorkflowElementType.PARALLEL_GATEWAY, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.PARALLEL_GATEWAY));
    assertEquals(WorkflowElementType.EVENT_BASED_GATEWAY, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.EVENT_BASED_GATEWAY));
    assertEquals(WorkflowElementType.SEQUENCE_FLOW, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.SEQUENCE_FLOW));
    assertEquals(WorkflowElementType.BUSINESS_RULE_TASK, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.BUSINESS_RULE_TASK));
    assertEquals(WorkflowElementType.SCRIPT_TASK, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.SCRIPT_TASK));
    assertEquals(WorkflowElementType.SEND_TASK, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.SEND_TASK));
    assertEquals(WorkflowElementType.UNKNOWN, Camunda8WorkflowViewer.elementTypeOf(
        ElementInstanceType.UNSPECIFIED));
    assertEquals(WorkflowElementType.UNKNOWN, Camunda8WorkflowViewer.elementTypeOf(null));

  }

}
