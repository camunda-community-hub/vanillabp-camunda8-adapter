package io.vanillabp.camunda8.wiring;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.FlowElement;

/**
 * The Camunda-managed user tasks of one BPMN process which no <code>&#64;WorkflowTask</code>
 * method serves, and the sentences a boot says about them.
 * <p>
 * Nothing here is a defect. The cluster creates the user task, it appears in a task list and
 * whoever finishes it moves the workflow on. The lifecycle listeners VanillaBP writes next to the
 * task are still answered, because the worker for their job type is opened whether a method is
 * there or not, so the workflow never waits for this application. That is why the core hands
 * such a task over as an OPTIONAL spec. What the application loses is the notification, and it
 * loses it without a word unless somebody says so once.
 * <p>
 * A user task a job worker serves is a different element and not read here. The workflow stands
 * at it, and the deployment refuses or reports it for its shape, see decision 53 in the
 * repository's DECISIONS.md.
 */
public final class Camunda8UnservedUserTasks {

  private Camunda8UnservedUserTasks() {
  }

  /**
   * One Camunda-managed user task of the model which no method serves.
   *
   * @param elementId The BPMN id of the element
   * @param name What the modeller wrote on it, or <code>null</code>
   * @param formReference The external form reference as the application knows it, which is the
   *          task definition of the user task
   */
  public record Unserved(
                         String elementId,
                         String name,
                         String formReference) {
  }

  /**
   * Reads the user tasks of one process which no method serves.
   * <p>
   * Asked with BOTH keys a task is wired by, the way the core's wiring validation matches a
   * method: a method names the task definition or the element id, and the task definition of a
   * user task is its external form reference. Asking by one key alone would name a task which
   * is served.
   * <p>
   * A reference written as a FEEL expression is left out. It names no method whatever the
   * application writes, and the deployment refuses or reports it for that reason already, see
   * decision 69 in the repository's DECISIONS.md. Saying a second thing about the same element
   * would only make the reader guess which of the two to fix.
   *
   * @param model The BPMN model, for the names the modeller wrote on the elements
   * @param userTasks The Camunda-managed user tasks of the process, as the deployment read them
   * @param plainFormReference Turns a reference as the cluster will know it back into the one
   *          the application knows
   * @param aMethodNames Whether a <code>&#64;WorkflowTask</code> method of the application names
   *          the given task definition or element id
   * @return The elements, in the order the model carries them, empty where every user task is
   *         served
   */
  public static List<Unserved> of(
      final BpmnModelInstance model,
      final List<Camunda8TaskWiring.Camunda8UserTaskToWire> userTasks,
      final UnaryOperator<String> plainFormReference,
      final Predicate<String> aMethodNames) {

    return userTasks
        .stream()
        .filter(userTask -> !Camunda8Scoping.isWrittenAsFeel(userTask.externalFormReference()))
        .map(userTask -> new Unserved(
            userTask.activityId(), nameOf(model, userTask.activityId()), plainFormReference
                .apply(userTask.externalFormReference())))
        .filter(element -> !aMethodNames.test(element.formReference()) && !aMethodNames.test(element.elementId()))
        .toList();

  }

  /**
   * @param model The model
   * @param elementId The element
   * @return What the modeller wrote on the element, or <code>null</code> where it carries
   *         nothing readable
   */
  private static String nameOf(
      final BpmnModelInstance model,
      final String elementId) {

    final var element = model.getModelElementById(elementId);
    if (!(element instanceof FlowElement flowElement)) {
      return null;
    }
    final var name = flowElement.getName();
    return (name == null) || name.isBlank()
        ? null
        : name;

  }

  /**
   * The report about one process, naming each element and the method which would serve it.
   *
   * @param unserved The elements, never empty
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param workflowModuleId The workflow module
   * @return The message, without the adapter id a log line puts in front of it
   */
  public static String report(
      final List<Unserved> unserved,
      final String bpmnProcessId,
      final String workflowModuleId) {

    final var message = new StringBuilder(
        """
            BPMN process '%s' of workflow module '%s' has %d user task(s) which no @WorkflowTask \
            method serves. The cluster creates such a task, it appears in a task list and whoever \
            finishes it moves the workflow on, so the workflow runs as modelled. What your \
            application does not get is the notification: no method of it is called when the task \
            is created, and none when the task is canceled. Where you want one, add it to the \
            class which claims this process:"""
            .formatted(bpmnProcessId, workflowModuleId, unserved.size()));
    unserved.forEach(element -> message.append(oneElement(element)));
    message
        .append(
            """

                Where a task list is all these tasks need, this line is the whole story and there is \
                nothing to do about it. VanillaBP completes such a task through \
                ProcessService#completeUserTask whether a method is notified about it or not.""");
    return message.toString();

  }

  /**
   * One element of the report: what to look for in the model, and what to write in the class.
   *
   * @param element The element
   * @return Its line, starting with a line break
   */
  private static String oneElement(
      final Unserved element) {

    final var describedElement = element.name() == null
        ? "user task '%s'".formatted(element.elementId())
        : "user task '%s' (named '%s' in the model)".formatted(element.elementId(), element.name());
    return """

          - %s, whose external form reference is '%s': add a method annotated with \
        @WorkflowTask(taskDefinition = "%s") or @WorkflowTask(id = "%s")."""
        .formatted(describedElement, element.formReference(), element.formReference(), element.elementId());

  }

}
