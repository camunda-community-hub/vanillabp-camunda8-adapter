package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which identifiers of its own models a workflow module reports while it deploys - the
 * question which tells a developer that two workflow modules of one application end up
 * under one name.
 * <p>
 * It costs no request: the adapter rewrites every one of those names while it scopes a
 * model, so it holds all of them at that moment, and what is under test here is that the
 * core is handed the names the APPLICATION knows rather than the ones the cluster will see.
 * A job type is among them and is the severe one on this BPMS - a worker subscribes to it
 * cluster-wide - while the job type of an element another runtime serves is none of this
 * module's names at all. A decision id belongs to the same report, read off the DMN file the
 * module brings, and the core scopes it by the workflow module alone like a message name.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8IdentifiersTheModelsDeclareTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static final String A_SECOND_MODULE = "payment-handling";

  private static final String DECISION_FILE = "credit-rating.dmn";

  private static final String DECISION_ID = "creditRating";

  private static final String A_DECISION_TABLE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="creditRatingDefinitions" name="Credit rating" namespace="http://vanillabp.io/c8-test">
        <decision id="creditRating" name="Credit rating">
          <decisionTable id="creditRatingTable" hitPolicy="UNIQUE">
            <input id="creditRatingInput" label="Approved">
              <inputExpression id="creditRatingInputExpression" typeRef="boolean">
                <text>approved</text>
              </inputExpression>
            </input>
            <output id="creditRatingOutput" label="Rating" name="rating" typeRef="string" />
          </decisionTable>
        </decision>
      </definitions>
      """;

  private static final String A_MODEL_DECLARING_EVERY_KIND = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:message id="Msg_1" name="PaymentReceived" />
        <bpmn:signal id="Sig_1" name="ApprovalRequested" />
        <bpmn:error id="Err_1" errorCode="CreditRefused" />
        <bpmn:escalation id="Esc_1" escalationCode="ManagerNeeded" />
        <bpmn:process id="LoanApproval" isExecutable="true">
          <bpmn:serviceTask id="Activity_Approve">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approve" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
          <bpmn:serviceTask id="Activity_Fetch" zeebe:modelerTemplate="io.camunda.connectors.HttpJson.v2">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="io.camunda:http-json:1" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
          <bpmn:userTask id="Activity_Sign">
            <bpmn:extensionElements>
              <zeebe:formDefinition externalReference="signForm" />
            </bpmn:extensionElements>
          </bpmn:userTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static BpmnModelInstance model() {

    return Bpmn
        .readModelFromStream(new ByteArrayInputStream(A_MODEL_DECLARING_EVERY_KIND.getBytes(StandardCharsets.UTF_8)));

  }

  private static InputStream aDecisionTable() {

    return new ByteArrayInputStream(A_DECISION_TABLE.getBytes(StandardCharsets.UTF_8));

  }

  /**
   * A deployment service of one adapter id, reporting to the given double.
   */
  private static Camunda8DeploymentService aDeploymentService(
      final TestScoping.ScopingDouble scoping) {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing listens on: not one question of this test reaches a cluster
    configuration.setRestAddress("http://localhost:65535");
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker(), scoping),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration, scoping);
    service
        .setAllowConnectorsResolver((
            workflowModuleId,
            bpmnProcessId) -> new Camunda8AllowConnectorsResolver.Setting(true, "vanillabp.adapters.c8.allow-connectors"));
    return service;

  }

  /**
   * Runs the pipeline stages which read the names of one workflow module and the report which
   * hands them over - the deploy command itself asks nothing about a name.
   */
  private static void readOneProcessAndReport(
      final Camunda8DeploymentService service,
      final String workflowModuleId) {

    final var context = service.prepareBpmn(workflowModuleId, null, FILE, PROCESS, model());
    service.reportWhatTheModelsDeclare(workflowModuleId, context);

  }

  /**
   * The same for a workflow module which brings a decision table along with its process.
   */
  private static void readOneProcessWithADecisionAndReport(
      final Camunda8DeploymentService service,
      final String workflowModuleId) {

    var context = service.prepareBpmn(workflowModuleId, null, FILE, PROCESS, model());
    context = service.readDmn(workflowModuleId, context, DECISION_FILE, aDecisionTable());
    service.reportWhatTheModelsDeclare(workflowModuleId, context);

  }

  /**
   * What one workflow module without any decision table reports.
   */
  private static List<ModelIdentifier> whatWasReported(
      final NameClashAvoidance mode) {

    final var scoping = TestScoping.of(mode);
    readOneProcessAndReport(aDeploymentService(scoping), MODULE);
    return scoping.getIdentifiersTheModelsDeclare();

  }

  @Test
  @DisplayName("Every kind the workflow module scopes is reported, by the name the application knows")
  public void everyKindIsReportedByItsPlainName() {

    final var reported = whatWasReported(NameClashAvoidance.USE_PREFIX);

    assertTrue(
        reported.contains(new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "PaymentReceived", null)),
        () -> "a message name belongs to the workflow module, so no process is named with it: "
            + reported);
    assertTrue(
        reported.contains(new ModelIdentifier(ScopedIdentifierKind.SIGNAL_NAME, "ApprovalRequested", null)),
        () -> "a signal name: "
            + reported);
    assertTrue(
        reported.contains(new ModelIdentifier(ScopedIdentifierKind.ERROR_CODE, "CreditRefused", null)),
        () -> "a BPMN error code: "
            + reported);
    assertTrue(
        reported.contains(new ModelIdentifier(ScopedIdentifierKind.ESCALATION_CODE, "ManagerNeeded", null)),
        () -> "an escalation code: "
            + reported);
    assertTrue(
        reported.contains(new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "approve", PROCESS)),
        () -> "a job type, with the BPMN process it belongs to - those are scoped per process: "
            + reported);
    assertTrue(
        reported.contains(new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "signForm", PROCESS)),
        () -> "a user task's form reference, which becomes a listener job type like any other: "
            + reported);

  }

  @Test
  @DisplayName("The job type of an element another runtime serves is not one of this module's names")
  public void aConnectorJobTypeIsNotReported() {

    final var reported = whatWasReported(NameClashAvoidance.USE_PREFIX);

    assertFalse(
        reported
            .stream()
            .anyMatch(identifier -> "io.camunda:http-json:1".equals(identifier.plainIdentifier())),
        () -> "that job type names a runtime somebody else deployed, and this adapter leaves it alone "
            + "everywhere: "
            + reported);

  }

  @Test
  @DisplayName("The names are the plain ones under every mode, prefixing included")
  public void theNamesAreThePlainOnesUnderEveryMode() {

    assertEquals(
        whatWasReported(NameClashAvoidance.USE_PREFIX),
        whatWasReported(NameClashAvoidance.NONE),
        "the core composes the form the cluster sees, so what it is handed does not depend on the mode");

  }

  @ParameterizedTest
  @EnumSource(NameClashAvoidance.class)
  @DisplayName("A decision the module deploys is reported under the id the application wrote")
  public void aDecisionIdIsReportedByItsPlainName(
      final NameClashAvoidance mode) {

    final var scoping = TestScoping.of(mode);

    readOneProcessWithADecisionAndReport(aDeploymentService(scoping), MODULE);

    assertTrue(
        scoping
            .getIdentifiersTheModelsDeclare()
            .contains(new ModelIdentifier(ScopedIdentifierKind.DMN_DECISION_ID, DECISION_ID, null)),
        () -> "a decision id is read off the file before the prefix is written into it, and it "
            + "carries no BPMN process because several processes of a module may call it: "
            + scoping.getIdentifiersTheModelsDeclare());

  }

  @Test
  @DisplayName("A workflow module which brings no decision table reports no decision id")
  public void aModuleWithoutADecisionTableReportsNone() {

    final var reported = whatWasReported(NameClashAvoidance.NONE);

    assertTrue(
        reported.stream().noneMatch(identifier -> identifier.kind() == ScopedIdentifierKind.DMN_DECISION_ID),
        () -> "nothing is invented for a module whose processes call no decision: "
            + reported);

  }

  @Test
  @DisplayName("One decision id in two workflow modules is reported for both of them")
  public void twoModulesSharingADecisionIdAreBothReported() {

    final var scoping = TestScoping.of(NameClashAvoidance.NONE);
    final var service = aDeploymentService(scoping);

    readOneProcessWithADecisionAndReport(service, MODULE);
    readOneProcessWithADecisionAndReport(service, A_SECOND_MODULE);

    assertEquals(
        List
            .of(
                new TestScoping.DeclaredByModule(
                    MODULE, new ModelIdentifier(ScopedIdentifierKind.DMN_DECISION_ID, DECISION_ID, null)),
                new TestScoping.DeclaredByModule(
                    A_SECOND_MODULE, new ModelIdentifier(ScopedIdentifierKind.DMN_DECISION_ID, DECISION_ID, null))),
        scoping
            .getModulesDeclaringIdentifiers()
            .stream()
            .filter(declared -> declared.identifier().kind() == ScopedIdentifierKind.DMN_DECISION_ID)
            .toList(),
        "the core sees one decision id declared by two modules, which under 'none' is the one "
            + "scoped form both of them reach the cluster with");

  }

  @Test
  @DisplayName("A decision several files of one workflow module name is one name of that module")
  public void oneModuleDeclaringADecisionTwiceReportsItOnce() {

    final var scoping = TestScoping.of(NameClashAvoidance.NONE);
    final var service = aDeploymentService(scoping);

    var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model());
    context = service.readDmn(MODULE, context, DECISION_FILE, aDecisionTable());
    context = service.readDmn(MODULE, context, "credit-rating-copy.dmn", aDecisionTable());
    service.reportWhatTheModelsDeclare(MODULE, context);

    assertEquals(
        List
            .of(
                new TestScoping.DeclaredByModule(
                    MODULE, new ModelIdentifier(ScopedIdentifierKind.DMN_DECISION_ID, DECISION_ID, null))),
        scoping
            .getModulesDeclaringIdentifiers()
            .stream()
            .filter(declared -> declared.identifier().kind() == ScopedIdentifierKind.DMN_DECISION_ID)
            .toList(),
        "the scope of a decision id is the workflow module, so the processes and the files of one "
            + "module sharing it is ordinary VanillaBP and reaches the core as a single name");

  }

}
