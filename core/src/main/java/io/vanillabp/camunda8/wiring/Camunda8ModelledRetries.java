package io.vanillabp.camunda8.wiring;

import java.util.Objects;
import java.util.stream.Stream;

import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.impl.BpmnModelConstants;
import io.camunda.zeebe.model.bpmn.impl.ZeebeConstants;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;

/**
 * Whether a BPMN model asks for retries of a failed job. It asks for them by writing the
 * attribute <code>retries</code> on the <code>zeebe:taskDefinition</code> of the element.
 * <p>
 * A job does not carry this answer. It carries the number of retries left, and that is 3
 * whether the model wrote <code>retries="3"</code> or nothing at all, because 3 is the
 * default of Camunda. So the answer comes from the model of the process version the job
 * belongs to. Why a job without the attribute is failed with no retries left is decision 83
 * in the repository's DECISIONS.md.
 */
public final class Camunda8ModelledRetries {

  private Camunda8ModelledRetries() {
  }

  /**
   * Reads the models of the process versions whose jobs a worker delivers.
   */
  @FunctionalInterface
  public interface Models {

    /**
     * Whether the model of one process version asks for retries of one element.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The PLAIN BPMN process ID
     * @param version The version of the process definition the job belongs to
     * @param elementId The BPMN element of the job
     * @return <code>true</code> where the model writes <code>retries</code>,
     *         <code>false</code> where it does not, and <code>null</code> where the model of
     *         that version could not be read
     */
    Boolean retriesModelledFor(
        String workflowModuleId,
        String bpmnProcessId,
        int version,
        String elementId);

  }

  /**
   * Whether one element of a model writes the attribute <code>retries</code> on its
   * <code>zeebe:taskDefinition</code>.
   *
   * @param model The model of one process version, may be <code>null</code>
   * @param elementId The BPMN element of the job
   * @return <code>true</code> or <code>false</code>, or <code>null</code> where there is
   *         no model or the model has no element of that id
   */
  public static Boolean retriesModelledFor(
      final BpmnModelInstance model,
      final String elementId) {

    if ((model == null) || (elementId == null)) {
      return null;
    }
    final ModelElementInstance element = model.getModelElementById(elementId);
    if (!(element instanceof BaseElement baseElement)) {
      return null;
    }
    final var taskDefinition = baseElement.getSingleExtensionElement(ZeebeTaskDefinition.class);
    if (taskDefinition == null) {
      return false;
    }
    return carriesTheRetriesAttribute(taskDefinition);

  }

  /**
   * Whether the attribute is written at all, whatever its value. The namespaces the model
   * declares as alternatives to the zeebe namespace are read as well. A diagram written
   * with another prefix for that namespace carries the very same attribute. Version 1 read
   * them in the same way.
   */
  static boolean carriesTheRetriesAttribute(
      final ModelElementInstance taskDefinition) {

    if (taskDefinition.getAttributeValue(ZeebeConstants.ATTRIBUTE_RETRIES) != null) {
      return true;
    }
    final var alternatives = taskDefinition
        .getModelInstance()
        .getModel()
        .getAlternativeNamespaces(BpmnModelConstants.ZEEBE_NS);
    return Stream
        .concat(
            Stream.of(BpmnModelConstants.ZEEBE_NS),
            alternatives == null
                ? Stream.<String>empty()
                : alternatives.stream())
        .map(namespace -> taskDefinition.getAttributeValueNs(namespace, ZeebeConstants.ATTRIBUTE_RETRIES))
        .anyMatch(Objects::nonNull);

  }

}
