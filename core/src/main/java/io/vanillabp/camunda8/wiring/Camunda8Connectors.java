package io.vanillabp.camunda8.wiring;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.impl.BpmnModelConstants;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;

/**
 * Which elements of a model belong to a runtime other than this application, and how the
 * adapter recognises them.
 *
 * <h2>What the marker says</h2>
 *
 * An element carrying the attribute {@code zeebe:modelerTemplate} was configured from an
 * ELEMENT TEMPLATE. A Camunda connector is the most common of those, and the property has
 * its name from it. But a company writes element templates for its own plain job-worker
 * tasks as well. That is why the marker alone decides nothing. The property
 * {@code allow-connectors} says whether this application reads the marker at all. Only
 * then does the model say WHICH elements another runtime serves.
 *
 * <h2>What an element has to carry to be left alone</h2>
 *
 * The marker AND a {@code zeebe:taskDefinition}. A connector runtime subscribes to the job
 * type of that task definition. So that is the whole mechanism. VanillaBP produces no task
 * spec for such an element and opens no worker for its job type. The runtime which owns
 * the type serves the job. An element carrying the marker without a task definition is
 * served by nothing this rule knows about. An inbound connector on a start event, for
 * example, correlates by a message name instead. So such an element stays where it was,
 * and the wiring validation says what it usually says about it.
 * <p>
 * A Camunda-managed user task is not among them either, on purpose, although VanillaBP 1
 * passed it over. The task list of the cluster serves a {@code zeebe:userTask}. An element
 * template on it presets an assignee or a form. It does not name the runtime of somebody
 * else.
 */
public final class Camunda8Connectors {

  private Camunda8Connectors() {
  }

  /**
   * The attribute which the element template of a modeller leaves on the element it
   * configured. It is read in the zeebe namespace, because the Camunda Modeler writes it
   * there.
   */
  public static final String ELEMENT_TEMPLATE_ATTRIBUTE = "modelerTemplate";

  /**
   * The property switching the whole rule on, without its adapter id.
   */
  public static final String ALLOW_CONNECTORS_KEY = "allow-connectors";

  /**
   * One element this workflow module leaves to the runtime which owns it.
   *
   * @param bpmnProcessId The BPMN process the element belongs to, as the application wrote
   *          it
   * @param elementId The BPMN element id
   * @param elementTemplate The value of {@code zeebe:modelerTemplate} on the element. It is
   *          the template id the modeller sees
   */
  public record ElementServedByAnotherRuntime(
                                              String bpmnProcessId,
                                              String elementId,
                                              String elementTemplate) {

    /**
     * The element in one line. This is how the startup report names it.
     *
     * @return The element as one line of the startup report
     */
    public String describe() {

      return "process '%s', element '%s', element template '%s'"
          .formatted(bpmnProcessId, elementId, elementTemplate);

    }

  }

  /**
   * The adapter-level key which switches the rule on, spelled out for a message.
   *
   * @param adapterId The adapter ID
   * @return {@code vanillabp.adapters.<id>.allow-connectors}
   */
  public static String propertyKeyOf(
      final String adapterId) {

    return Camunda8AdapterConfiguration.propertyKey(adapterId, ALLOW_CONNECTORS_KEY);

  }

  /**
   * The value of {@code zeebe:modelerTemplate} on the element, or <code>null</code> where
   * the element carries none.
   * <p>
   * The namespace comes from the model API and not from a literal. The namespaces the model
   * declares as alternatives to it are read as well. A diagram written with an aliased zeebe
   * prefix carries the very same attribute under a namespace of its own. Reading only the
   * canonical one would silently pass such a model over.
   *
   * @param element The BPMN element
   * @return The element template id, or <code>null</code>
   */
  public static String elementTemplateOf(
      final BaseElement element) {

    final var alternatives = element
        .getModelInstance()
        .getModel()
        .getAlternativeNamespaces(BpmnModelConstants.ZEEBE_NS);
    return Stream
        .concat(
            Stream.of(BpmnModelConstants.ZEEBE_NS),
            alternatives == null
                ? Stream.<String>empty()
                : alternatives.stream())
        .map(namespace -> element.getAttributeValueNs(namespace, ELEMENT_TEMPLATE_ATTRIBUTE))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);

  }

  /**
   * Whether a runtime other than this application serves the element. That is the case
   * when it was built from an element template AND names a job type of its own. That other
   * runtime subscribes to this job type.
   *
   * @param element The BPMN element
   * @return Whether VanillaBP leaves the element alone where connectors are allowed
   */
  public static boolean isServedByAnotherRuntime(
      final BaseElement element) {

    return (element
        .getSingleExtensionElement(ZeebeTaskDefinition.class) != null) && (elementTemplateOf(element) != null);

  }

  /**
   * Every element of one process which a runtime other than this application serves. The
   * startup report names them one by one.
   * <p>
   * The search walks through the task definitions of the model, not through a list of
   * element types. So it reaches an ad-hoc subprocess, a message throw event and an end
   * event as well. The wiring collects none of those elements. Under name-clash avoidance
   * {@code use-prefix}, their job type would otherwise be rewritten.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process id as it stands in the model at this point
   * @return The elements, in document order
   */
  public static List<ElementServedByAnotherRuntime> elementsServedByAnotherRuntime(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var found = new LinkedList<ElementServedByAnotherRuntime>();
    for (final var taskDefinition : model.getModelElementsByType(ZeebeTaskDefinition.class)) {
      final var element = owningElementOf(taskDefinition);
      if ((element == null) || !bpmnProcessId.equals(owningProcessIdOf(element))) {
        continue;
      }
      final var elementTemplate = elementTemplateOf(element);
      if (elementTemplate == null) {
        continue;
      }
      found.add(new ElementServedByAnotherRuntime(bpmnProcessId, element.getId(), elementTemplate));
    }
    return found;

  }

  /**
   * The BPMN element an extension element belongs to. A task definition sits inside
   * {@code bpmn:extensionElements}, which carries no id of its own.
   *
   * @param extensionElement The extension element
   * @return The element carrying it, or <code>null</code> where there is none
   */
  public static BaseElement owningElementOf(
      final ModelElementInstance extensionElement) {

    var current = extensionElement.getParentElement();
    while (current != null) {
      if (current instanceof BaseElement element) {
        return element;
      }
      current = current.getParentElement();
    }
    return null;

  }

  /**
   * The id of the {@code bpmn:process} an element belongs to.
   *
   * @param element The BPMN element
   * @return The process id, or <code>null</code> for an element outside any process
   */
  private static String owningProcessIdOf(
      final BaseElement element) {

    ModelElementInstance current = element;
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

  /**
   * The three levels the property is read at, as a copy-pasteable block for a message.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module id, or <code>&lt;m&gt;</code> where the
   *          message speaks about no particular module
   * @param bpmnProcessId The BPMN process id, or <code>&lt;w&gt;</code> where the message
   *          speaks about no particular process
   * @return Three indented lines, the least specific level first
   */
  public static String levelsOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return """
        vanillabp.adapters.%s.%s
        vanillabp.workflow-modules.%s.adapters.%s.%s
        vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"""
        .formatted(
            adapterId,
            ALLOW_CONNECTORS_KEY,
            workflowModuleId,
            adapterId,
            ALLOW_CONNECTORS_KEY,
            workflowModuleId,
            bpmnProcessId,
            adapterId,
            ALLOW_CONNECTORS_KEY);

  }

  /**
   * The line which opens and closes the startup report. Plain ASCII, so a log viewer
   * without a font for box drawing shows a line rather than a row of question marks, and
   * nothing else this adapter logs is framed: a second framed message would cost this one
   * its effect.
   */
  public static final String FRAME_LINE = "=".repeat(100);

  /**
   * What an application gives up while it runs connectors, in the words of the startup
   * report. It is kept here, because the same sentences also belong into the guidance
   * which a boot WITHOUT the property writes. A reader must find them unchanged in both
   * places.
   */
  public static final String WHAT_IT_COSTS = """
      VanillaBP validates none of these elements against your @WorkflowTask methods, opens no job \
      worker for their job types and cannot tell whether anything serves them. Where no connector \
      runtime runs on this cluster, or where it does not see this tenant, a workflow reaches such \
      an element, stops there, and its job ends in an incident once the retries are used up. \
      Nothing a boot can ask detects that in advance, which is why this message exists.""";

  /**
   * Reports keys the application set at TASK level, where this one does not resolve. Then it
   * lets the boot go on. The value changes no answer. Ending a boot over a key which simply
   * does nothing would be worse than saying so.
   *
   * @param adapterId The adapter ID
   * @param keysAtTaskLevel The keys found, fully spelled out. Nothing is reported for an
   *          empty list
   * @param warnLogger Where the guidance goes
   */
  public static void reportKeysSetAtTaskLevel(
      final String adapterId,
      final List<String> keysAtTaskLevel,
      final Consumer<String> warnLogger) {

    if (keysAtTaskLevel.isEmpty()) {
      return;
    }
    warnLogger.accept(
        """
            Camunda 8 adapter '%s' has '%s' set at TASK level: %s. That level does not resolve this \
            key and the value changes nothing. A task level is keyed by the task DEFINITION, and the \
            task definition of a connector is the connector's own type, which every element using \
            that connector shares. Which single element is left to another runtime is decided by the \
            model instead, by the attribute 'zeebe:modelerTemplate' on the element. The key is read \
            at these three levels, the most specific configured one winning:
            %s"""
            .formatted(
                adapterId,
                ALLOW_CONNECTORS_KEY,
                String.join(", ", keysAtTaskLevel),
                levelsOf(adapterId, "<m>", "<w>")));

  }

}
