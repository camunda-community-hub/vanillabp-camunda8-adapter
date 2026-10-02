package io.vanillabp.camunda8.wiring;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.CompletionCondition;
import io.camunda.zeebe.model.bpmn.instance.ConditionExpression;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.TimerEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeLoopCharacteristics;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeSubscription;
import io.vanillabp.integration.adapter.spi.expressions.ExpressionPlace;
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;

/**
 * The expressions one BPMN process reads the workflow's data with, as the core is told
 * about them: the element they sit in, the place inside that element and the text FEEL
 * evaluates.
 * <p>
 * FEEL marks an expression with a leading <code>=</code>, and everything after it is the
 * expression. So a value without that sign is a value and nothing is reported about it: a
 * timer written as <code>PT1H</code> and a collection written as a JSON list are what the
 * modeller meant, not something the cluster evaluates.
 * <p>
 * Read are the places a modeller puts a data read into and which this cluster has: the
 * condition of a sequence flow, the definition of a timer, the collection and the
 * completion condition of a multi-instance element, and the correlation key of a message.
 * The correlation key is the one place Camunda 7 has not got, because this cluster
 * correlates a message by a value the model names.
 * <p>
 * Three places of the list the core keeps do not exist here. Camunda 8 deploys no
 * conditional event, no standard loop and no multi-instance cardinality, so a model
 * carrying one of them never reaches this adapter. What is left out on purpose is what the
 * cluster resolves for itself: the job type of a task, the process of a call activity, the
 * decision of a business rule task, the form of a user task. Those name a target rather
 * than reading data, and a getter on the workflow aggregate is no answer for them.
 * <p>
 * Asked while the model is still the modeller's. This adapter writes a correlation key of
 * its own into a message which carries none and input mappings into a multi-instance
 * element, so a later reading would report VanillaBP's own expressions as the modeller's.
 */
public final class Camunda8ModelExpressions {

  private Camunda8ModelExpressions() {
  }

  /**
   * The expressions of one executable process, in the order the places are read.
   *
   * @param model The BPMN model of one file, as it was read
   * @param bpmnProcessId The process id as it stands in the model
   * @return The expressions, empty where the process carries none
   */
  public static List<ModelExpression> of(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var process = model.getModelElementById(bpmnProcessId);
    if (!(process instanceof Process)) {
      return List.of();
    }
    final var expressions = new ArrayList<ModelExpression>();
    // a condition expression belongs to a sequence flow here and to nothing else, since
    // this cluster has no conditional event to put one on
    model
        .getModelElementsByType(ConditionExpression.class)
        .stream()
        .filter(condition -> belongsTo(condition, process))
        .forEach(condition -> add(
            expressions,
            elementIdOf(condition),
            ExpressionPlace.SEQUENCE_FLOW_CONDITION,
            condition.getTextContent()));
    // a timer holds its definition in a child element, and a model names one of the three
    model
        .getModelElementsByType(TimerEventDefinition.class)
        .stream()
        .filter(timer -> belongsTo(timer, process))
        .forEach(timer -> {
          final var elementId = elementIdOf(timer);
          textOf(timer.getTimeDuration())
              .ifPresent(text -> add(expressions, elementId, ExpressionPlace.TIMER, text));
          textOf(timer.getTimeDate()).ifPresent(text -> add(expressions, elementId, ExpressionPlace.TIMER, text));
          textOf(timer.getTimeCycle()).ifPresent(text -> add(expressions, elementId, ExpressionPlace.TIMER, text));
        });
    model
        .getModelElementsByType(ZeebeLoopCharacteristics.class)
        .stream()
        .filter(loop -> belongsTo(loop, process))
        .forEach(loop -> add(
            expressions,
            elementIdOf(loop),
            ExpressionPlace.MULTI_INSTANCE_COLLECTION,
            loop.getInputCollection()));
    model
        .getModelElementsByType(CompletionCondition.class)
        .stream()
        .filter(condition -> belongsTo(condition, process))
        .forEach(condition -> add(
            expressions,
            elementIdOf(condition),
            ExpressionPlace.MULTI_INSTANCE_COMPLETION_CONDITION,
            condition.getTextContent()));
    // the correlation key sits on the MESSAGE, which belongs to the file rather than to
    // the process, so the catch elements of this process are what leads to it. The element
    // named is the one waiting, because that is what a modeller searches their model for
    Camunda8TaskWiring
        .messageCatchElementsOf(model, bpmnProcessId)
        .forEach(catchElement -> {
          final var subscription = catchElement
              .message()
              .getSingleExtensionElement(ZeebeSubscription.class);
          if (subscription != null) {
            add(
                expressions,
                catchElement.elementId(),
                ExpressionPlace.MESSAGE_CORRELATION_KEY,
                subscription.getCorrelationKey());
          }
        });
    return List.copyOf(expressions);

  }

  /**
   * Adds one value, if FEEL evaluates it at all.
   *
   * @param expressions What is being collected
   * @param elementId The BPMN element the value was read from
   * @param place Where in that element it sits
   * @param value The value as the model carries it
   */
  private static void add(
      final List<ModelExpression> expressions,
      final String elementId,
      final ExpressionPlace place,
      final String value) {

    if (!Camunda8Scoping.isWrittenAsFeel(value)) {
      return;
    }
    final var text = value.trim();
    expressions.add(new ModelExpression(elementId, place, text, text.substring(1)));

  }

  /**
   * The text of an element a timer may or may not have.
   *
   * @param element The child element holding a duration, a date or a cycle
   * @return Its text, empty where the element is absent or says nothing
   */
  private static Optional<String> textOf(
      final ModelElementInstance element) {

    if ((element == null) || (element.getTextContent() == null) || element.getTextContent().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(element.getTextContent());

  }

  /**
   * Whether the element sits inside the given process - a file may hold several.
   */
  private static boolean belongsTo(
      final ModelElementInstance element,
      final ModelElementInstance process) {

    for (var candidate = element; candidate != null; candidate = candidate.getParentElement()) {
      if (candidate == process) {
        return true;
      }
    }
    return false;

  }

  /**
   * The ID of the element itself or of the closest ancestor having one - a condition
   * expression carries no ID, its sequence flow does, and a
   * <code>zeebe:loopCharacteristics</code> sits two elements below its activity.
   */
  private static String elementIdOf(
      final ModelElementInstance element) {

    for (var candidate = element; candidate != null; candidate = candidate.getParentElement()) {
      final var id = candidate
          .getDomElement()
          .getAttribute("id");
      if ((id != null) && !id.isBlank()) {
        return id;
      }
    }
    return "unknown element";

  }

}
