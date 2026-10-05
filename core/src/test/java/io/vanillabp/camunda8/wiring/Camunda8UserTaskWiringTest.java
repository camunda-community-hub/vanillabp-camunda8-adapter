package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The V1-COMPATIBILITY contract of the user-task listener injection:
 * listener job types keep the V1 prefix, retries stay "0" and the insertion order
 * is EXACTLY V1's (VanillaBP "creating" FIRST, custom listeners in between,
 * VanillaBP "canceling" LAST) - upgrading a V1 application must produce a
 * byte-identical BPMN so no new process version is deployed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UserTaskWiringTest {

  private static BpmnModelInstance model(
      final String userTaskExtensions) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="UTProcess" isExecutable="true">
            <bpmn:userTask id="ut">
              <bpmn:extensionElements>
        %s
              </bpmn:extensionElements>
            </bpmn:userTask>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(userTaskExtensions);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static List<ZeebeTaskListener> listenersOf(
      final BpmnModelInstance model) {

    return List.copyOf(model
        .getModelElementsByType(UserTask.class)
        .iterator()
        .next()
        .getSingleExtensionElement(ZeebeTaskListeners.class)
        .getTaskListeners());

  }

  @Test
  @DisplayName("V1 order: VanillaBP creating FIRST, custom listeners in between, VanillaBP canceling LAST")
  public void listenersInsertedInV1Order() {

    final var bpmn = model("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="approve" />
        <zeebe:taskListeners>
          <zeebe:taskListener eventType="creating" type="custom-listener" />
        </zeebe:taskListeners>""");

    final var userTasks = Camunda8TaskWiring.userTasksOf(bpmn, "UTProcess", "mod", "x.bpmn");

    assertEquals(1, userTasks.size());
    assertEquals("approve", userTasks.get(0).externalFormReference());
    assertEquals("io.vanillabp.userTask:approve", userTasks.get(0).listenerJobType());

    final var listeners = listenersOf(bpmn);
    assertEquals(3, listeners.size());
    // VanillaBP creating FIRST
    assertEquals(ZeebeTaskListenerEventType.creating, listeners.get(0).getEventType());
    assertEquals("io.vanillabp.userTask:approve", listeners.get(0).getType());
    assertEquals(
        "1",
        listeners.get(0).getRetries(),
        "one attempt is what a gateway can hand back when it lost the delivery");
    // custom listener stays in between
    assertEquals("custom-listener", listeners.get(1).getType());
    // VanillaBP canceling LAST
    assertEquals(ZeebeTaskListenerEventType.canceling, listeners.get(2).getEventType());
    assertEquals("io.vanillabp.userTask:approve", listeners.get(2).getType());
    assertEquals(
        "1",
        listeners.get(2).getRetries(),
        "one attempt is what a gateway can hand back when it lost the delivery");

  }

  @Test
  @DisplayName("Re-wiring an already-processed model does not duplicate the listeners")
  public void rewiringDoesNotDuplicate() {

    final var bpmn = model("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="approve" />""");

    Camunda8TaskWiring.userTasksOf(bpmn, "UTProcess", "mod", "x.bpmn");
    Camunda8TaskWiring.userTasksOf(bpmn, "UTProcess", "mod", "x.bpmn");

    assertEquals(2, listenersOf(bpmn).size(), "creating + canceling exactly once");

  }

  @Test
  @DisplayName("A user task without an external form reference fails with a guiding message")
  public void missingFormReferenceFailsGuiding() {

    final var bpmn = model("""
        <zeebe:userTask />""");

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> Camunda8TaskWiring.userTasksOf(bpmn, "UTProcess", "mod", "x.bpmn"));
    assertTrue(failure.getMessage().contains("External form reference"));

  }

  @Test
  @DisplayName("Worker-based user tasks (no zeebe:userTask marker) are ignored here")
  public void workerBasedUserTasksIgnored() {

    final var bpmn = model("""
        <zeebe:taskDefinition type="legacyUserTaskJob" />""");

    assertEquals(0, Camunda8TaskWiring.userTasksOf(bpmn, "UTProcess", "mod", "x.bpmn").size());

  }

  @Test
  @DisplayName("A user task as VanillaBP 1 modelled it up to 1.6.3 is found, so it can be reported")
  public void aUserTaskCarryingVersionOnesFormKeyIsFound() {

    final var bpmn = model("""
        <zeebe:formDefinition formKey="camunda-forms:bpmn:approve" />""");

    final var found = Camunda8TaskWiring.jobWorkerUserTasksOf(bpmn, "UTProcess");

    assertEquals(
        List.of("ut"),
        found.withVersionOnesFormKey(),
        "a user task whose form definition names a formKey is version 1's construction");
    assertEquals(
        List.of(),
        found.withoutAFormKey(),
        "and it is named as that shape, because a search of the models for 'formKey' finds it");
    assertEquals(
        0,
        Camunda8TaskWiring.userTasksOf(bpmn, "UTProcess", "mod", "x.bpmn").size(),
        "nothing else sees it, which is why it has to be reported on its own");

  }

  @Test
  @DisplayName("A user task without a formKey is found as well, as the other shape")
  public void aUserTaskWithoutAFormKeyIsFoundAsTheOtherShape() {

    final var carryingACamundaForm = model("""
        <zeebe:formDefinition formId="approve" />""");
    final var carryingNothingAtAll = model("");

    assertEquals(
        List.of("ut"),
        Camunda8TaskWiring.jobWorkerUserTasksOf(carryingACamundaForm, "UTProcess").withoutAFormKey(),
        "a user task without 'zeebe:userTask' is one a job worker serves, whatever form it names");
    assertEquals(
        List.of("ut"),
        Camunda8TaskWiring.jobWorkerUserTasksOf(carryingNothingAtAll, "UTProcess").withoutAFormKey(),
        "a user task carrying no extension at all is the same case, and the quietest of them");
    assertEquals(
        List.of(),
        Camunda8TaskWiring.jobWorkerUserTasksOf(carryingNothingAtAll, "UTProcess").withVersionOnesFormKey(),
        "no formKey is on it, so the sentence about version 1's convention must not be said");

  }

  @Test
  @DisplayName("A Camunda-managed user task is served, whatever else it carries")
  public void aCamundaManagedUserTaskIsServed() {

    final var bpmn = model("""
        <zeebe:userTask />
        <zeebe:formDefinition externalReference="approve" formKey="camunda-forms:bpmn:approve" />""");

    assertTrue(
        Camunda8TaskWiring.jobWorkerUserTasksOf(bpmn, "UTProcess").isEmpty(),
        "the zeebe:userTask marker decides, and a leftover formKey next to it changes nothing");

  }

  @Test
  @DisplayName("A user task carrying a task definition of its own is none of this")
  public void aUserTaskWithAnOwnJobWorkerIsNoneOfThis() {

    final var bpmn = model("""
        <zeebe:taskDefinition type="ownWorker" />
        <zeebe:formDefinition formKey="camunda-forms:bpmn:approve" />""");

    assertTrue(
        Camunda8TaskWiring.jobWorkerUserTasksOf(bpmn, "UTProcess").isEmpty(),
        "a task definition of its own says the application serves the element, so it is no user "
            + "task of VanillaBP's and the reader passes over it");

  }

  @Test
  @DisplayName("A user task of ANOTHER process is not counted")
  public void userTaskOfAnotherProcessIsNotCounted() {

    final var bpmn = model("""
        <zeebe:formDefinition formKey="camunda-forms:bpmn:approve" />""");

    assertTrue(Camunda8TaskWiring.jobWorkerUserTasksOf(bpmn, "SomeOtherProcess").isEmpty());

  }

}
