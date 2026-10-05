package io.vanillabp.camunda8;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * The core's name-clash avoidance reduced to what an adapter test needs of it: a mode per
 * workflow module, the prefixing the core would do in that mode, and what the adapter
 * reported about the names a BPMS holds. The adapter is tested against the SPI rather than
 * against the core's implementation, which this module deliberately does not depend on.
 */
public final class TestScoping {

  private TestScoping() {
  }

  /**
   * @param mode What every workflow module's mode is
   * @return A support answering that mode and prefixing accordingly
   */
  public static ScopingDouble of(
      final NameClashAvoidance mode) {

    return new ScopingDouble(workflowModuleId -> mode);

  }

  /**
   * The same double with a mode PER workflow module, which the core resolves per module as
   * well: a mixed configuration is what lets one module's prefixed identifier meet another
   * module's plain one, and it is what decides whether two modules share a tenant.
   *
   * @param modePerWorkflowModule The mode of a workflow module, answered for every id a test
   *          lets the adapter ask about, the <code>null</code> id included
   * @return A support answering those modes and prefixing accordingly
   */
  public static ScopingDouble of(
      final Function<String, NameClashAvoidance> modePerWorkflowModule) {

    return new ScopingDouble(modePerWorkflowModule);

  }

  /**
   * The double, which also KEEPS what the adapter reported to it: the three reports of the
   * name-clash avoidance are what an adapter asking its cluster about a name is measured
   * by, so a test reads them back here instead of against the core.
   */
  public static final class ScopingDouble implements NameClashAvoidanceSupport {

    private final Function<String, NameClashAvoidance> modes;

    private final List<IdentifierHeldElsewhere> identifiersTheBpmsAlreadyHolds = new ArrayList<>();

    private final List<ModelIdentifier> identifiersTheModelsDeclare = new ArrayList<>();

    private final List<DeclaredByModule> modulesDeclaringIdentifiers = new ArrayList<>();

    private final List<ModelIdentifier> identifiersOfHeldVersions = new ArrayList<>();

    private ScopingDouble(
        final Function<String, NameClashAvoidance> modes) {

      this.modes = modes;

    }

    /**
     * @return What the adapter said the BPMS already holds
     */
    public List<IdentifierHeldElsewhere> getIdentifiersTheBpmsAlreadyHolds() {

      return identifiersTheBpmsAlreadyHolds;

    }

    /**
     * @return What the adapter said the models of a workflow module declare
     */
    public List<ModelIdentifier> getIdentifiersTheModelsDeclare() {

      return identifiersTheModelsDeclare;

    }

    /**
     * The same answers, each with the workflow module it was reported for. The core keys its
     * collision check by the module, so a test about two modules sharing a name has to see
     * which side a name came from.
     *
     * @return One entry per identifier and workflow module
     */
    public List<DeclaredByModule> getModulesDeclaringIdentifiers() {

      return modulesDeclaringIdentifiers;

    }

    /**
     * @return What the CORE was told about the versions a BPMS still holds - reported by
     *         the core itself, so a test of an adapter reads this only where it plays the
     *         core
     */
    public List<ModelIdentifier> getIdentifiersOfHeldVersions() {

      return identifiersOfHeldVersions;

    }

    @Override
    public void reportIdentifiersTheBpmsAlreadyHolds(
        final String adapterId,
        final String workflowModuleId,
        final Collection<IdentifierHeldElsewhere> found) {

      identifiersTheBpmsAlreadyHolds.addAll(found);

    }

    @Override
    public void reportIdentifiersTheModelsDeclare(
        final String adapterId,
        final String workflowModuleId,
        final Collection<ModelIdentifier> declared) {

      identifiersTheModelsDeclare.addAll(declared);
      declared
          .forEach(identifier -> modulesDeclaringIdentifiers.add(new DeclaredByModule(workflowModuleId, identifier)));

    }

    @Override
    public void reportIdentifiersOfHeldVersion(
        final String adapterId,
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version,
        final Long activeWorkflows,
        final Collection<ModelIdentifier> declared) {

      if (declared != null) {
        identifiersOfHeldVersions.addAll(declared);
      }

    }

    private boolean prefixes(
        final String workflowModuleId) {

      return modes.apply(workflowModuleId) == NameClashAvoidance.USE_PREFIX;

    }

    @Override
    public NameClashAvoidance modeFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId) {

      return modes.apply(workflowModuleId);

    }

    @Override
    public String scopedProcessId(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId) {

      return prefixes(workflowModuleId)
          ? String.join(SEPARATOR, workflowModuleId, bpmnProcessId)
          : bpmnProcessId;

    }

    @Override
    public String scopedIdentifier(
        final String workflowModuleId,
        final String identifier,
        final String adapterId) {

      return prefixes(workflowModuleId) && (identifier != null)
          ? String.join(SEPARATOR, workflowModuleId, identifier)
          : identifier;

    }

    @Override
    public String scopedTaskDefinition(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinition,
        final String adapterId) {

      return prefixes(workflowModuleId) && (taskDefinition != null)
          ? String.join(SEPARATOR, workflowModuleId, bpmnProcessId, taskDefinition)
          : taskDefinition;

    }

    @Override
    public String plainProcessId(
        final String workflowModuleId,
        final String scopedBpmnProcessId,
        final String adapterId) {

      final var prefix = workflowModuleId + SEPARATOR;
      return scopedBpmnProcessId.startsWith(prefix)
          ? scopedBpmnProcessId.substring(prefix.length())
          : scopedBpmnProcessId;

    }

    @Override
    public String plainIdentifier(
        final String workflowModuleId,
        final String scopedIdentifier,
        final String adapterId) {

      return plainProcessId(workflowModuleId, scopedIdentifier, adapterId);

    }

    @Override
    public String plainTaskDefinition(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String scopedTaskDefinition,
        final String adapterId) {

      if (scopedTaskDefinition == null) {
        // like the core's implementation: a task carrying no task definition keeps its
        // null all the way to the wiring validation, which is what reports it
        return null;
      }
      final var prefix = String.join(SEPARATOR, workflowModuleId, bpmnProcessId, "");
      return scopedTaskDefinition.startsWith(prefix)
          ? scopedTaskDefinition.substring(prefix.length())
          : scopedTaskDefinition;

    }

    @Override
    public void validateNoneNameClashStrategy(
        final String adapterId,
        final String byAdapterOnlyPropertyKey) {

    }

    @Override
    public void validateNativeIsolationSupported(
        final String adapterId,
        final String workflowModuleId,
        final String bpmsDescription) {

    }

    @Override
    public void validateNoCollidingProcessIds(
        final String adapterId,
        final Collection<DeployedProcess> deployedProcesses) {

    }

  }

  /**
   * One identifier a workflow module declared, as the adapter reported it.
   *
   * @param workflowModuleId The workflow module the report was about
   * @param identifier What it declares
   */
  public record DeclaredByModule(
                                 String workflowModuleId,
                                 NameClashAvoidanceSupport.ModelIdentifier identifier) {
  }

}
