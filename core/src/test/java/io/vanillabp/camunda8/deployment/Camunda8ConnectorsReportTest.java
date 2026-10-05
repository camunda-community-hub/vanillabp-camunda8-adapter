package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about the elements a workflow module handed to another runtime, and what
 * it says about the ones it did not hand over although the model looks as if it wanted to.
 * <p>
 * The cluster is an address nothing listens on: none of the three messages asks it
 * anything, and what is under test is the text a reader gets.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8ConnectorsReportTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static BpmnModelInstance modelWithAConnector() {

    return model("""
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
        """);

  }

  private static BpmnModelInstance modelWithoutAConnector() {

    return model("""
            <bpmn:serviceTask id="Activity_Approve">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="approve" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
        """);

  }

  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * Runs the pipeline stages which fill the module's report, and writes it.
   */
  private static String deploy(
      final CapturedOutput output,
      final Camunda8DeploymentService service,
      final BpmnModelInstance model) {

    // what THIS test logged: the capture spans the whole class, and every case here
    // asserts the absence of something an earlier case wrote
    final var before = output.getAll().length();
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    service.reportWhatConnectorsCost(MODULE, context);
    return output.getAll().substring(before);

  }

  @Test
  @DisplayName("A module which allows connectors gets a framed report naming key, module and elements")
  public void theReportNamesWhatWasHandedOver(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(NameClashAvoidance.BY_ADAPTER, allowedBy("vanillabp.adapters.c8.allow-connectors")),
        modelWithAConnector());
    assertTrue(
        logged.contains("CONNECTORS ARE SWITCHED ON: WORKFLOW MODULE 'loan-approval'"),
        () -> "a heading a reader finds without reading a word: "
            + logged);
    assertTrue(
        logged.contains("===================="),
        () -> "and the frame around it: "
            + logged);
    assertTrue(
        logged.contains("vanillabp.adapters.c8.allow-connectors"),
        () -> "the key which switched it on: "
            + logged);
    assertTrue(
        logged.contains("element 'Activity_Fetch'") && logged.contains("io.camunda.connectors.HttpJson.v2"),
        () -> "the element and the template a modeller recognises it by: "
            + logged);
    assertFalse(
        logged.contains("Activity_Approve"),
        () -> "and not the task this application does serve: "
            + logged);
    assertTrue(
        logged.contains("opens no job worker"),
        () -> "what it costs at runtime: "
            + logged);
    assertTrue(
        logged.contains("stops being portable"),
        () -> "and what it costs the model: "
            + logged);
    assertFalse(
        logged.contains("'use-prefix' leaves the job types above unprefixed"),
        () -> "the prefix sentence belongs to the prefix mode alone: "
            + logged);

  }

  @Test
  @DisplayName("Under use-prefix the report says that the connector's job type stays unscoped")
  public void underPrefixingTheReportSaysWhatItCosts(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(NameClashAvoidance.USE_PREFIX, allowedBy("vanillabp.adapters.c8.allow-connectors")),
        modelWithAConnector());
    assertTrue(
        logged.contains("'use-prefix' leaves the job types above unprefixed"),
        () -> "the one sentence which only this mode earns: "
            + logged);

  }

  @Test
  @DisplayName("A switch nobody needs is one line, not a frame")
  public void aSwitchNobodyNeedsIsOneLine(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(
            NameClashAvoidance.BY_ADAPTER,
            allowedBy("vanillabp.workflow-modules.loan-approval.adapters.c8.allow-connectors")),
        modelWithoutAConnector());
    assertTrue(
        logged.contains("no element of it is built from an element template"),
        () -> "the switch is on and nothing uses it, which is worth a sentence: "
            + logged);
    assertTrue(
        logged.contains("vanillabp.workflow-modules.loan-approval.adapters.c8.allow-connectors"),
        () -> "named at the level it was set: "
            + logged);
    assertFalse(
        logged.contains("CONNECTORS ARE SWITCHED ON"),
        () -> "and no frame: "
            + logged);

  }

  @Test
  @DisplayName("Without the property the adapter guides towards it before the core's validation runs")
  public void withoutThePropertyTheAdapterGuidesTowardsIt(
      final CapturedOutput output) {

    final var logged = deploy(output, adapter(NameClashAvoidance.BY_ADAPTER, null), modelWithAConnector());
    assertTrue(
        logged.contains("built from an element template"),
        () -> "what the adapter saw in the model: "
            + logged);
    assertTrue(
        logged.contains("vanillabp.adapters.c8.allow-connectors") && logged
            .contains("vanillabp.workflow-modules.loan-approval.adapters.c8.allow-connectors") && logged.contains(
                "vanillabp.workflow-modules.loan-approval.workflows.LoanApproval.adapters.c8.allow-connectors"),
        () -> "all three levels the key is read at: "
            + logged);
    assertFalse(
        logged.contains("CONNECTORS ARE SWITCHED ON"),
        () -> "and nothing was handed over: "
            + logged);

  }

  @Test
  @DisplayName("A passed-over element reaches the core's wiring validation as nothing at all")
  public void thePassedOverElementIsNotAmongTheSpecs(
      final CapturedOutput output) {

    final var specs = new ArrayList<String>();
    final var core = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public void validateTaskWiring(
          final String workflowModuleId,
          final String bpmnProcessId,
          final Collection<BpmnTaskSpec> tasks) {

        tasks
            .stream()
            .map(BpmnTaskSpec::taskDefinition)
            .forEach(specs::add);

      }

    };

    deploy(
        output,
        adapterServedBy(core, allowedBy("vanillabp.adapters.c8.allow-connectors")),
        modelWithAConnector());

    assertTrue(specs.contains("approve"), () -> "the task this application serves: "
        + specs);
    assertFalse(
        specs.contains("io.camunda:http-json:1"),
        () -> "the connector produces no task spec, so the core never asks for a @WorkflowTask method "
            + "for it - and a method which DID name that job type is reported as unwired by the core's "
            + "per-module check: "
            + specs);

  }

  private static Camunda8AllowConnectorsResolver allowedBy(
      final String propertyKey) {

    return (
        workflowModuleId,
        bpmnProcessId) -> new Camunda8AllowConnectorsResolver.Setting(true, propertyKey);

  }

  private static Camunda8DeploymentService adapter(
      final NameClashAvoidance mode,
      final Camunda8AllowConnectorsResolver allowConnectorsResolver) {

    return adapterServedBy(new Camunda8DeploymentServiceTest.NoOpInvoker(), mode, allowConnectorsResolver);

  }

  private static Camunda8DeploymentService adapterServedBy(
      final Camunda8DeploymentServiceTest.NoOpInvoker core,
      final Camunda8AllowConnectorsResolver allowConnectorsResolver) {

    return adapterServedBy(core, NameClashAvoidance.BY_ADAPTER, allowConnectorsResolver);

  }

  /**
   * An adapter against an address nothing listens on, with the given core, the given
   * scoping mode and the given answer about connectors.
   */
  private static Camunda8DeploymentService adapterServedBy(
      final Camunda8DeploymentServiceTest.NoOpInvoker core,
      final NameClashAvoidance mode,
      final Camunda8AllowConnectorsResolver allowConnectorsResolver) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(mode);
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(core, scoping),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration, scoping);
    service.setAllowConnectorsResolver(allowConnectorsResolver);
    return service;

  }

}
