package io.vanillabp.camunda8.deployment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.impl.BpmnModelConstants;
import io.camunda.zeebe.model.bpmn.instance.Activity;
import io.camunda.zeebe.model.bpmn.instance.Process;

/**
 * The activities of a BPMN process which carry a standard loop, and what this adapter says
 * about them.
 * <p>
 * A modeller draws <code>standardLoopCharacteristics</code> to repeat one activity while a
 * condition holds. Camunda 8 does not implement it: the cluster deploys the model, runs the
 * activity once and moves on, with no error, no incident and no log line. Measured on 8.8.39,
 * 8.9.21 and 8.10.0-rc1. So a model this boot deploys is refused where the application claims
 * its process. A process nobody claims is somebody else's model and is not looked at (see
 * decision 73 of {@code DECISIONS.md}). A version the cluster already holds is
 * reported where workflows still run on it, because nobody can change that model any more.
 * <p>
 * The model API has no type for the marker. It keeps the element in the XML all the same,
 * which is where it is looked for.
 */
public final class Camunda8StandardLoops {

  private Camunda8StandardLoops() {
  }

  /**
   * What the cluster does with a standard loop, and the two forms which do repeat an activity.
   * Shared by the refusal and the warning, so both say the same.
   */
  private static final String WHAT_THE_CLUSTER_DOES = """
      Camunda 8 does not run a standard loop: it runs the activity once, the workflow moves on, \
      and the cluster says nothing about it. Two forms do repeat an activity. Draw a loop in the \
      sequence flow: a gateway after the activity leads back to it while the condition holds. Or \
      make the activity a multi-instance element: a handler then learns its round from \
      @MultiInstanceElement, @MultiInstanceIndex and @MultiInstanceTotal.""";

  /**
   * The IDs of the activities of one BPMN process which carry
   * <code>standardLoopCharacteristics</code>, at any depth of its subprocesses, in the order
   * of the model.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The BPMN process ID as the model carries it
   * @return The element IDs, possibly empty
   */
  public static List<String> elementIdsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return model
        .getModelElementsByType(Activity.class)
        .stream()
        .filter(activity -> bpmnProcessId.equals(owningProcessIdOf(activity)))
        .filter(activity -> !activity
            .getDomElement()
            .getChildElementsByNameNs(BpmnModelConstants.BPMN20_NS, "standardLoopCharacteristics")
            .isEmpty())
        .map(Activity::getId)
        .toList();

  }

  private static String owningProcessIdOf(
      final Activity activity) {

    ModelElementInstance current = activity;
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

  /**
   * The message which ends the deployment of a model carrying a standard loop.
   *
   * @param elementIds The activities carrying one, at least one
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param workflowModuleId The workflow module ID
   * @return The text of the refusal
   */
  public static String refusal(
      final List<String> elementIds,
      final String bpmnProcessId,
      final String workflowModuleId) {

    return """
        BPMN process '%s' of workflow module '%s' carries a standard loop \
        (standardLoopCharacteristics) on %s! %s Change the model to one of the two forms and \
        deploy it again."""
        .formatted(bpmnProcessId, workflowModuleId, described(elementIds), WHAT_THE_CLUSTER_DOES);

  }

  /**
   * One version the cluster holds which carries a standard loop, as the warning names it.
   *
   * @param version The version the cluster assigned
   * @param elementIds The activities carrying one
   * @param running How many workflows run on that version, or <code>null</code> where the
   *          cluster could not be asked
   */
  public record HeldVersion(
                            String version,
                            List<String> elementIds,
                            Long running) {
  }

  /**
   * The versions among the given held models which carry a standard loop while workflows
   * still run on them. A model is read first and counted only where it carries the marker,
   * because counting is a search on the cluster. A version no workflow runs on any more is
   * left out: it can do no harm.
   *
   * @param heldModels The models the cluster holds under one BPMN process id
   * @param scopedBpmnProcessId The BPMN process ID as the cluster knows it
   * @param runningOn How many workflows run on a version, <code>null</code> where the cluster
   *          could not be asked
   * @return The versions to warn about, possibly empty
   */
  public static List<HeldVersion> heldVersionsToWarnAbout(
      final Collection<Camunda8ModelsTheClusterHolds.HeldModel> heldModels,
      final String scopedBpmnProcessId,
      final Function<String, Long> runningOn) {

    final var found = new ArrayList<HeldVersion>();
    for (final var heldModel : heldModels) {
      final var elementIds = elementIdsOf(heldModel.model(), scopedBpmnProcessId);
      if (elementIds.isEmpty()) {
        continue;
      }
      final var running = runningOn.apply(heldModel.version());
      if ((running != null) && (running == 0L)) {
        continue;
      }
      found.add(new HeldVersion(heldModel.version(), elementIds, running));
    }
    return found;

  }

  /**
   * The warning about a version the cluster holds which carries a standard loop.
   *
   * @param heldVersion The version and what it carries
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param workflowModuleId The workflow module ID
   * @return The text of the warning
   */
  public static String warningAboutAHeldVersion(
      final HeldVersion heldVersion,
      final String bpmnProcessId,
      final String workflowModuleId) {

    final var running = heldVersion.running();
    return """
        Version '%s' of BPMN process '%s' (workflow module '%s') carries a standard loop \
        (standardLoopCharacteristics) on %s, and %s. %s The cluster holds that model already, so \
        nobody can change it and the boot goes on. Let those workflows end, or migrate them to a \
        version which uses one of the two forms."""
        .formatted(
            heldVersion.version(),
            bpmnProcessId,
            workflowModuleId,
            described(heldVersion.elementIds()),
            running == null
                ? "the cluster could not say how many workflows still run on it"
                : running == 1L
                    ? "one workflow still runs on it"
                    : "%d workflows still run on it".formatted(running),
            WHAT_THE_CLUSTER_DOES);

  }

  private static String described(
      final List<String> elementIds) {

    final var quoted = elementIds
        .stream()
        .collect(Collectors.joining("', '", "'", "'"));
    return elementIds.size() == 1
        ? "the activity %s".formatted(quoted)
        : "the activities %s".formatted(quoted);

  }

}
