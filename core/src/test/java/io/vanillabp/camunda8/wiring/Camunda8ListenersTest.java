package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which listeners of a model this adapter treats as the modeller's, and which it leaves
 * alone because VanillaBP or one of its extensions wrote them.
 * <p>
 * Every case is asserted together with a listener which must NOT be affected: the whole
 * point of the recognition is telling the two apart, and a rule reaching too far would end
 * a boot over a listener the framework wrote itself.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ListenersTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

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
   * A user task carrying a listener somebody modelled next to the lifecycle listener
   * VanillaBP writes, which is what every upgraded model looks like.
   */
  private static final String A_MODELLED_LISTENER_NEXT_TO_VANILLABPS_OWN = """
          <bpmn:userTask id="Activity_Approve">
            <bpmn:extensionElements>
              <zeebe:userTask />
              <zeebe:formDefinition externalReference="approve" />
              <zeebe:taskListeners>
                <zeebe:taskListener eventType="creating" type="io.vanillabp.userTask:approve" retries="0" />
                <zeebe:taskListener eventType="completing" type="auditTheApproval" />
              </zeebe:taskListeners>
            </bpmn:extensionElements>
          </bpmn:userTask>
      """;

  @Test
  @DisplayName("A modelled task listener is recognised and VanillaBP's own is not")
  public void aModelledTaskListenerIsRecognised() {

    final var listeners = Camunda8Listeners
        .listenersOf(model(A_MODELLED_LISTENER_NEXT_TO_VANILLABPS_OWN), PROCESS);

    assertEquals(1, listeners.size(), () -> "one listener belongs to the application: "
        + listeners);
    final var listener = listeners.getFirst();
    assertEquals("auditTheApproval", listener.taskDefinition(), "the job type IS the task definition");
    assertEquals("completing", listener.event(), "and the event the modeller chose");
    assertEquals(
        Camunda8Listeners.Kind.TASK_LISTENER,
        listener.kind(),
        "a task listener, which is the only kind a zeebe:userTask takes");
    assertEquals("Activity_Approve", listener.elementId(), "named by the element a modeller finds");

  }

  @Test
  @DisplayName("An execution listener of any element is recognised as well")
  public void anExecutionListenerIsRecognised() {

    final var listeners = Camunda8Listeners
        .listenersOf(model("""
                <bpmn:endEvent id="Event_Done">
                  <bpmn:extensionElements>
                    <zeebe:executionListeners>
                      <zeebe:executionListener eventType="end" type="archiveTheOrder" />
                    </zeebe:executionListeners>
                  </bpmn:extensionElements>
                </bpmn:endEvent>
            """), PROCESS);

    assertEquals(1, listeners.size(), () -> "version 1 never served one of these: "
        + listeners);
    assertEquals(Camunda8Listeners.Kind.EXECUTION_LISTENER, listeners.getFirst().kind());
    assertEquals("archiveTheOrder", listeners.getFirst().taskDefinition());
    assertEquals("end", listeners.getFirst().event());

  }

  @Test
  @DisplayName("A listener of a Business Cockpit extension is left alone like the adapter's own")
  public void anExtensionsListenerIsLeftAlone() {

    final var listeners = Camunda8Listeners
        .listenersOf(model("""
                <bpmn:endEvent id="Event_Done">
                  <bpmn:extensionElements>
                    <zeebe:executionListeners>
                      <zeebe:executionListener eventType="end" type="io.vanillabp.businesscockpit:TestProcess" />
                    </zeebe:executionListeners>
                  </bpmn:extensionElements>
                </bpmn:endEvent>
            """), PROCESS);

    assertTrue(
        listeners.isEmpty(),
        () -> "an extension's prefix starts with the framework's, so one rule covers both: "
            + listeners);
    assertTrue(Camunda8Listeners.isVanillaBpsOwn("io.vanillabp.businesscockpit:X"));
    assertTrue(Camunda8Listeners.isVanillaBpsOwn("io.vanillabp.workflowEnd:X"));
    assertFalse(Camunda8Listeners.isVanillaBpsOwn("auditTheApproval"));
    assertFalse(Camunda8Listeners.isVanillaBpsOwn(null), "an unfinished listener is nobody's");

  }

  @Test
  @DisplayName("A listener of another process of the same file is not read for this one")
  public void aListenerOfAnotherProcessIsNotRead() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="TestProcess" isExecutable="true">
            <bpmn:endEvent id="Event_Here">
              <bpmn:extensionElements>
                <zeebe:executionListeners>
                  <zeebe:executionListener eventType="end" type="here" />
                </zeebe:executionListeners>
              </bpmn:extensionElements>
            </bpmn:endEvent>
          </bpmn:process>
          <bpmn:process id="OtherProcess" isExecutable="true">
            <bpmn:endEvent id="Event_There">
              <bpmn:extensionElements>
                <zeebe:executionListeners>
                  <zeebe:executionListener eventType="end" type="there" />
                </zeebe:executionListeners>
              </bpmn:extensionElements>
            </bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;
    final var model = Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

    assertEquals(
        List.of("here"),
        Camunda8Listeners
            .listenersOf(model, "TestProcess")
            .stream()
            .map(Camunda8Listeners.ModelledListener::taskDefinition)
            .toList(),
        "the property is keyed by the process, so the reading has to be too");
    assertEquals(
        List.of("there"),
        Camunda8Listeners
            .listenersOf(model, "OtherProcess")
            .stream()
            .map(Camunda8Listeners.ModelledListener::taskDefinition)
            .toList());

  }

  @Test
  @DisplayName("Two listeners of one element under one job type are found as the pair they are")
  public void twoListenersUnderOneJobTypeAreFound() {

    final var listeners = Camunda8Listeners
        .listenersOf(model("""
                <bpmn:userTask id="Activity_Approve">
                  <bpmn:extensionElements>
                    <zeebe:userTask />
                    <zeebe:formDefinition externalReference="approve" />
                    <zeebe:taskListeners>
                      <zeebe:taskListener eventType="creating" type="auditTheApproval" />
                      <zeebe:taskListener eventType="canceling" type="auditTheApproval" />
                    </zeebe:taskListeners>
                  </bpmn:extensionElements>
                </bpmn:userTask>
            """), PROCESS);

    final var sharing = Camunda8Listeners.listenersSharingAJobType(listeners);
    assertEquals(1, sharing.size(), () -> "one element, one job type, two events: "
        + sharing);
    assertEquals(
        List.of("creating", "canceling"),
        sharing
            .getFirst()
            .stream()
            .map(Camunda8Listeners.ModelledListener::event)
            .toList(),
        "version 1 picked one of these by findFirst and nobody could tell which");

  }

  @Test
  @DisplayName("Two listeners of one element under two job types are two tasks, not a clash")
  public void twoListenersUnderTwoJobTypesAreNoClash() {

    final var listeners = Camunda8Listeners
        .listenersOf(model("""
                <bpmn:userTask id="Activity_Approve">
                  <bpmn:extensionElements>
                    <zeebe:userTask />
                    <zeebe:formDefinition externalReference="approve" />
                    <zeebe:taskListeners>
                      <zeebe:taskListener eventType="creating" type="theApprovalStarts" />
                      <zeebe:taskListener eventType="canceling" type="theApprovalIsGone" />
                    </zeebe:taskListeners>
                  </bpmn:extensionElements>
                </bpmn:userTask>
            """), PROCESS);

    assertEquals(2, listeners.size());
    assertTrue(
        Camunda8Listeners.listenersSharingAJobType(listeners).isEmpty(),
        "a job type per event is what makes the event part of the identity");

  }

  @Test
  @DisplayName("The three levels are printed so a reader can paste them")
  public void theLevelsArePrintedAsABlock() {

    assertEquals(
        """
            vanillabp.adapters.c8.allow-listeners
            vanillabp.workflow-modules.loans.adapters.c8.allow-listeners
            vanillabp.workflow-modules.loans.workflows.Loan.adapters.c8.allow-listeners""",
        Camunda8Listeners.levelsOf("c8", "loans", "Loan"),
        "least specific first, which is the order a reader decides in");
    assertEquals(
        "vanillabp.adapters.c8.allow-listeners",
        Camunda8Listeners.propertyKeyOf("c8"));

  }

  @Test
  @DisplayName("A key at task level earns one warning naming the levels which work")
  public void aKeyAtTaskLevelIsReported() {

    final var warnings = new ArrayList<String>();

    Camunda8Listeners.reportKeysSetAtTaskLevel("c8", List.of(), warnings::add);
    assertTrue(warnings.isEmpty(), "a configuration which does nothing wrong says nothing");

    Camunda8Listeners
        .reportKeysSetAtTaskLevel(
            "c8",
            List.of("vanillabp.workflow-modules.loans.workflows.Loan.tasks.approve.adapters.c8.allow-listeners"),
            warnings::add);
    assertEquals(1, warnings.size());
    assertTrue(
        warnings.getFirst().contains("does not resolve this key"),
        () -> "what the value does, which is nothing: "
            + warnings);
    assertTrue(
        warnings.getFirst().contains("vanillabp.workflow-modules.<m>.adapters.c8.allow-listeners"),
        () -> "and where it would work: "
            + warnings);

  }

  @Test
  @DisplayName("Under use-prefix a served listener's job type is scoped like any task definition")
  public void underPrefixingAServedListenersJobTypeIsScoped() {

    final var model = model(A_MODELLED_LISTENER_NEXT_TO_VANILLABPS_OWN);

    Camunda8Scoping
        .apply(model, MODULE, "c8", TestScoping.of(NameClashAvoidance.USE_PREFIX), null, (
            bpmnProcessId,
            jobType) -> "auditTheApproval".equals(jobType));

    final var jobTypes = model
        .getModelElementsByType(ZeebeTaskListener.class)
        .stream()
        .map(ZeebeTaskListener::getType)
        .toList();
    assertTrue(
        jobTypes.contains("test-module__TestProcess__auditTheApproval"),
        () -> "a worker of this application serves it, so two modules must not share it: "
            + jobTypes);
    assertTrue(
        jobTypes.contains("io.vanillabp.userTask:approve"),
        () -> "and the framework's own listener is left exactly as it was: "
            + jobTypes);

  }

  @Test
  @DisplayName("Without the property nothing of a model's job types is rewritten")
  public void withoutThePropertyNoJobTypeIsRewritten() {

    final var model = model(A_MODELLED_LISTENER_NEXT_TO_VANILLABPS_OWN);

    Camunda8Scoping.apply(model, MODULE, "c8", TestScoping.of(NameClashAvoidance.USE_PREFIX), null, null);

    assertTrue(
        model
            .getModelElementsByType(ZeebeTaskListener.class)
            .stream()
            .map(ZeebeTaskListener::getType)
            .toList()
            .contains("auditTheApproval"),
        "renaming a job type nobody serves would rename something this application does not own");

  }

}
