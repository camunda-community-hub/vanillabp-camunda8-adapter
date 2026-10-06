package io.vanillabp.camunda8.deployment;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;

/**
 * The multi-instance elements around a BPMN task which hand no item over, and the refusal a
 * handler asking for one deserves.
 * <p>
 * A multi-instance element tells a handler three things: which round it is in, how many
 * rounds there are, and the item of the round. This engine walks a collection, so the first
 * two are always there. The item is the variable the model names in the
 * <code>inputElement</code> of its <code>zeebe:loopCharacteristics</code>, and a model may
 * name none: the cluster then hands each instance its entry of the collection under no name
 * at all, so a <code>&#64;MultiInstanceElement</code> for that element reads
 * <code>null</code> while the workflow runs.
 * <p>
 * Such a model is legitimate on its own, and so is a handler which reads the index and the
 * total only. What nobody means is the two together, which is why the refusal needs both
 * halves: this adapter reads the BPMN, and the core answers which elements a handler wants
 * the item of.
 */
public final class Camunda8MultiInstanceItems {

  private Camunda8MultiInstanceItems() {
  }

  /**
   * The IDs of the multi-instance elements enclosing the given BPMN element which name no
   * <code>inputElement</code>.
   * <p>
   * Read off the chain this deployment just recorded. Asked while one process is wired, that
   * chain holds the elements of the process itself and nothing else: the levels a CALLER
   * contributes are linked only once every process of the workflow module is wired. Those are
   * judged then, by {@link #inheritedElementsWithoutAnItem}. A method in the called process
   * asks for the item by the caller's element id, so the caller's own model cannot answer that
   * question.
   *
   * @param registry The chains recorded while deploying
   * @param bpmnProcessId The process' ID as the CLUSTER will know it (the SCOPED ID)
   * @param elementId The BPMN element the handler serves
   * @return The element IDs, possibly empty
   */
  public static Set<String> elementsWithoutAnItem(
      final Camunda8MultiInstance.Registry registry,
      final String bpmnProcessId,
      final String elementId) {

    return registry
        .chainOf(bpmnProcessId, elementId)
        .stream()
        .filter(element -> element.elementVariable() == null)
        .map(Camunda8MultiInstance.MultiInstanceElement::elementId)
        .collect(Collectors.toCollection(LinkedHashSet::new));

  }

  /**
   * The multi-instance elements of the CALLERS of a process which enclose the given BPMN
   * element and name no <code>inputElement</code>.
   * <p>
   * Only what the call sites contribute, so nothing found by
   * {@link #elementsWithoutAnItem} while the process itself was wired is found a second time.
   * Asked once the call graph of the workflow module is linked, and before anything is sent to
   * the cluster.
   *
   * @param registry The chains recorded while deploying, with the call graph linked
   * @param bpmnProcessId The called process' ID as the CLUSTER will know it (the SCOPED ID)
   * @param elementId The BPMN element the handler serves
   * @return The element IDs, each with the calling process declaring it as the CLUSTER knows
   *         it, possibly empty
   */
  public static Map<String, String> inheritedElementsWithoutAnItem(
      final Camunda8MultiInstance.Registry registry,
      final String bpmnProcessId,
      final String elementId) {

    final var found = new LinkedHashMap<String, String>();
    registry
        .inheritedLevelsOf(bpmnProcessId, elementId)
        .stream()
        .filter(level -> level.element().elementVariable() == null)
        .forEach(level -> found.putIfAbsent(level.element().elementId(), level.bpmnProcessId()));
    return found;

  }

  /**
   * One handler asking for an item its model never hands over.
   *
   * @param taskElementId The BPMN element the handler serves
   * @param taskDefinition The task definition it was wired by
   * @param elementIds The multi-instance elements it wants the item of, all of them
   *          without one
   * @param callers For an element a calling process declares, that process' PLAIN ID by
   *          element ID. Empty where every element stands in the process of the handler
   */
  public record Finding(
                        String taskElementId,
                        String taskDefinition,
                        Collection<String> elementIds,
                        Map<String, String> callers) {

    /**
     * A finding about elements of the handler's own process.
     *
     * @param taskElementId The BPMN element the handler serves
     * @param taskDefinition The task definition it was wired by
     * @param elementIds The multi-instance elements it wants the item of
     */
    public Finding(
        final String taskElementId,
        final String taskDefinition,
        final Collection<String> elementIds) {
      this(taskElementId, taskDefinition, elementIds, Map.of());
    }

  }

  /**
   * The message every finding of one BPMN process is reported in.
   *
   * @param findings What the model cannot answer, at least one
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param workflowModuleId The workflow module ID
   * @return The text of the refusal
   */
  public static String refusal(
      final List<Finding> findings,
      final String bpmnProcessId,
      final String workflowModuleId) {

    final var message = new StringBuilder(
        """
            A @WorkflowTask method of BPMN process '%s' (workflow module '%s') asks for the item \
            of an iteration which has no item to give!"""
            .formatted(bpmnProcessId, workflowModuleId));
    findings
        .forEach(finding -> message
            .append(
                """

                      - the method serving task '%s' (task definition '%s') declares \
                    @MultiInstanceElement for %s no 'inputElement', so nothing says what the \
                    value of a round is called."""
                    .formatted(
                        finding.taskElementId(),
                        finding.taskDefinition(),
                        described(finding.elementIds(), finding.callers()))));
    message
        .append(
            """

                Write that attribute on the loop characteristics of each element named here \
                (<zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />), \
                which is the 'Input element' field of the modeller. Or drop the parameter: \
                @MultiInstanceIndex and @MultiInstanceTotal are answered by every multi-instance \
                element. Without one of the two the parameter receives null as soon as a job \
                arrives, and nothing says why.""");
    return message.toString();

  }

  /**
   * The elements of one finding as the message names them, ending in the verb which agrees
   * with their number - so the sentence around it reads as one sentence either way.
   */
  private static String described(
      final Collection<String> elementIds,
      final Map<String, String> callers) {

    // an element of a caller is named with its process: the reader has two models in front of
    // them, and the handler's own one does not hold it
    final var named = elementIds
        .stream()
        .map(elementId -> callers.containsKey(elementId)
            ? "'%s' (in the calling BPMN process '%s')".formatted(elementId, callers.get(elementId))
            : "'%s'".formatted(elementId))
        .collect(Collectors.joining(", "));
    return elementIds.size() == 1
        ? "the element %s, which names".formatted(named)
        : "the elements %s, which name".formatted(named);

  }

}
