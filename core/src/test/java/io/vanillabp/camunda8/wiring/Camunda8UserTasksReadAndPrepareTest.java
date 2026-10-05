package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Reading the user tasks of a model and preparing them are two calls, and this pins what
 * separates them.
 * <p>
 * An extension runs in the same deployment pipeline, on the same files, and needs the same
 * list to place its own wiring. It reads with {@code readUserTasksOf}, which touches
 * nothing. {@code userTasksOf} is the deployment path and writes the lifecycle listeners,
 * and it promises that a second call adds no second set of them - a promise the adapter's
 * own re-wiring of a model the cluster holds already relies on.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UserTasksReadAndPrepareTest {

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

  private static ZeebeTaskListeners listenersOf(
      final BpmnModelInstance model) {

    return model
        .getModelElementsByType(UserTask.class)
        .iterator()
        .next()
        .getSingleExtensionElement(ZeebeTaskListeners.class);

  }

  private static List<ZeebeTaskListener> listenerList(
      final BpmnModelInstance model) {

    final var listeners = listenersOf(model);
    return listeners == null
        ? List.of()
        : List.copyOf(listeners.getTaskListeners());

  }

  private static final String A_CAMUNDA_MANAGED_USER_TASK = """
      <zeebe:userTask />
      <zeebe:formDefinition externalReference="approve" />""";

  @Test
  @DisplayName("Reading the user tasks of a model changes nothing in it")
  public void readingChangesNothing() {

    final var bpmn = model(A_CAMUNDA_MANAGED_USER_TASK);

    final var userTasks = Camunda8TaskWiring.readUserTasksOf(bpmn, "UTProcess", "mod", "x.bpmn");

    assertEquals(1, userTasks.size(), "the Camunda-managed user task is reported");
    assertEquals("approve", userTasks.get(0).externalFormReference(),
        "with the reference which IS its task definition");
    assertNull(
        listenersOf(bpmn),
        "and the model is untouched: an extension asking what a file declares may not write into it");

  }

  @Test
  @DisplayName("Preparing reports the same user tasks and writes the lifecycle listeners")
  public void preparingReportsTheSameAndWrites() {

    final var bpmn = model(A_CAMUNDA_MANAGED_USER_TASK);

    final var read = Camunda8TaskWiring.readUserTasksOf(bpmn, "UTProcess", "mod", "x.bpmn");
    final var prepared = Camunda8TaskWiring.userTasksOf(bpmn, "UTProcess", "mod", "x.bpmn");

    assertEquals(read, prepared, "the two calls report the same user tasks");
    assertEquals(2, listenerList(bpmn).size(), "and preparing added the creating and the canceling listener");

  }

  @Test
  @DisplayName("A second preparing call adds no second set of listeners")
  public void preparingTwiceAddsNothing() {

    final var bpmn = model(A_CAMUNDA_MANAGED_USER_TASK);

    Camunda8TaskWiring.userTasksOf(bpmn, "UTProcess", "mod", "x.bpmn");
    final var afterTheFirstCall = List.copyOf(listenerList(bpmn));
    Camunda8TaskWiring.userTasksOf(bpmn, "UTProcess", "mod", "x.bpmn");

    assertEquals(
        afterTheFirstCall.size(),
        listenerList(bpmn).size(),
        "a user task which already carries this listener job type is left as it is");

  }

  @Test
  @DisplayName("A user task without an external form reference is refused while it is read")
  public void aUserTaskWithoutAReferenceIsRefused() {

    final var bpmn = model("<zeebe:userTask />");

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> Camunda8TaskWiring.readUserTasksOf(bpmn, "UTProcess", "mod", "x.bpmn"));

    assertEquals(
        true,
        refusal.getMessage().contains("External form reference"),
        () -> "the message says what to set in the modeler: "
            + refusal.getMessage());

  }

}
