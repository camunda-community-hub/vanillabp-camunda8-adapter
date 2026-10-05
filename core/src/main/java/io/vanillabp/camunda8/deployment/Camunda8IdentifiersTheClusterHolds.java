package io.vanillabp.camunda8.deployment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.camunda.client.api.search.response.SearchResponse;
import io.vanillabp.camunda8.processservice.Camunda8SearchPages;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.IdentifierHeldElsewhere;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks the cluster which identifiers of a workflow module it ALREADY held when this
 * deployment arrived - another application's BPMN process id, a decision id somebody
 * deployed years ago. The core words the warning out of what is found here; this class
 * only asks and describes what came back.
 * <p>
 * Two kinds can be asked about, because the cluster keeps a searchable record of them: a
 * BPMN process id and a DMN decision id. The process ids of a whole workflow module go
 * into ONE search, since the filter takes a list of them; a decision id has to be asked
 * for on its own, since its filter takes one exact string and nothing else.
 *
 * <h2>Why a finding is a guess and is still reported</h2>
 *
 * A cluster records no owner. What a definition carries is the resource it was deployed
 * from, its version and its key, so the only thing this adapter can compare against is
 * what THIS deployment brought: the resource names the cluster reported back for it, and
 * for a decision the decision requirements the DMN file declares. A definition under one
 * of those markers is this application's own, older deployments included, and stays
 * silent. Everything else is reported, and reported as unproven: a second application is
 * free to deploy a file of the same name, and renaming a file of our own makes our own
 * earlier definition look like somebody else's. The core turns that into a sentence which
 * says the line may be harmless, which is the only honest way to report a guess - and
 * staying silent instead would leave the mode {@code none} unguarded on exactly the
 * cluster where {@code none} is the answer, a cluster without multi-tenancy.
 *
 * <h2>What this must never do</h2>
 *
 * End a boot. Every search is wrapped, a failure is logged at debug and the rest of the
 * questions are still put, because a diagnostic which takes an application down is worse
 * than the clash it looks for. See decision 25 in the repository's DECISIONS.md.
 */
@Slf4j
public final class Camunda8IdentifiersTheClusterHolds {

  /**
   * Where the paging stops regardless of what the cluster still offers, which is 2000
   * definitions. Lower than what {@link Camunda8SearchPages} allows a caller by default: a
   * diagnostic asks a bounded number of questions or it is not a diagnostic any more, and a
   * cursor which never ends would hold up a boot. A search here is bounded by the processes
   * of one workflow module times the versions the cluster holds of them, so one page
   * normally answers it and the paging exists for the module which outgrows that.
   */
  static final int MAX_PAGES = 20;

  private Camunda8IdentifiersTheClusterHolds() {
    // static helper
  }

  /**
   * One BPMN process this deployment brought to the cluster.
   *
   * @param plainBpmnProcessId The process id as the application knows it
   * @param scopedBpmnProcessId The process id as the cluster knows it - what the search
   *          asks for
   * @param resourceName The file the cluster reported for it, which is the marker telling
   *          an older definition of ours from a foreign one
   * @param version The version the cluster assigned to it
   */
  public record DeployedProcess(
                                String plainBpmnProcessId,
                                String scopedBpmnProcessId,
                                String resourceName,
                                int version) {
  }

  /**
   * One DMN decision this deployment brought to the cluster.
   *
   * @param plainDecisionId The decision id as the application knows it
   * @param scopedDecisionId The decision id as the cluster knows it - what the search asks
   *          for
   * @param decisionRequirementsId The DRD the decision belongs to, which is the marker
   *          telling an older deployment of ours from a foreign one: it names the DMN file
   *          and survives every version of it
   * @param version The version the cluster assigned to it
   */
  public record DeployedDecision(
                                 String plainDecisionId,
                                 String scopedDecisionId,
                                 String decisionRequirementsId,
                                 int version) {
  }

  /**
   * Asks the cluster about the identifiers of one workflow module, after that module was
   * deployed - the versions this boot got are part of the answer.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module which was deployed
   * @param tenantId The tenant the module lives in, or <code>null</code> where the mode
   *          uses none
   * @param client The client of this adapter instance
   * @param processes What this deployment brought, per BPMN process
   * @param decisions What this deployment brought, per DMN decision
   * @return What the cluster holds beyond this application's own deployments, empty where
   *         it holds nothing of the kind and where it could not be asked
   */
  public static Collection<IdentifierHeldElsewhere> askTheCluster(
      final String adapterId,
      final String workflowModuleId,
      final String tenantId,
      final CamundaClient client,
      final Collection<DeployedProcess> processes,
      final Collection<DeployedDecision> decisions) {

    final var found = new ArrayList<IdentifierHeldElsewhere>();
    found.addAll(processIdsHeldElsewhere(adapterId, workflowModuleId, tenantId, client, processes));
    found.addAll(decisionIdsHeldElsewhere(adapterId, workflowModuleId, tenantId, client, decisions));
    return found;

  }

  /**
   * The process definitions the cluster holds under the ids of this workflow module which
   * this deployment did not bring. One search for the whole module: the filter takes the
   * list of ids, and the state filter is the one every definition search of this adapter
   * uses, so a definition an operator deleted stays out of the answer.
   */
  private static List<IdentifierHeldElsewhere> processIdsHeldElsewhere(
      final String adapterId,
      final String workflowModuleId,
      final String tenantId,
      final CamundaClient client,
      final Collection<DeployedProcess> processes) {

    if ((processes == null) || processes.isEmpty()) {
      return List.of();
    }
    final var scopedIds = processes
        .stream()
        .map(DeployedProcess::scopedBpmnProcessId)
        .distinct()
        .toList();
    final var plainIdsByScopedId = processes
        .stream()
        .collect(
            Collectors
                .toMap(
                    DeployedProcess::scopedBpmnProcessId,
                    DeployedProcess::plainBpmnProcessId,
                    (
                        first,
                        second) -> first));
    final var ourResourceNames = processes
        .stream()
        .map(DeployedProcess::resourceName)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    final var definitions = everyPage(
        adapterId,
        workflowModuleId,
        "process definitions",
        cursor -> client
            .newProcessDefinitionSearchRequest()
            // one filter lambda per request: a second filter(...) call on this client
            // REPLACES the first, so everything the search asks for is set right here
            .filter(filter -> {
              Camunda8ProcessVersions.onlyDefinitionsWhichStillCount(filter);
              filter.processDefinitionId(processDefinitionId -> processDefinitionId.in(scopedIds));
              if (tenantId != null) {
                filter.tenantId(tenantId);
              }
            })
            .page(page -> {
              page.limit(Integer.valueOf(Camunda8SearchPages.PAGE_SIZE));
              if (cursor != null) {
                page.after(cursor);
              }
            })
            .send()
            .join());
    return whatOfThoseDefinitionsIsNotOurs(definitions, plainIdsByScopedId, ourResourceNames);

  }

  /**
   * The findings among the process definitions which came back: everything the resource name
   * does not attribute to this deployment.
   *
   * @param definitions What the cluster answered with
   * @param plainIdsByScopedId The plain id per id the cluster knows, for the report
   * @param ourResourceNames The resources this deployment was recorded under
   * @return One finding per definition which is not one of ours
   */
  private static List<IdentifierHeldElsewhere> whatOfThoseDefinitionsIsNotOurs(
      final List<ProcessDefinition> definitions,
      final Map<String, String> plainIdsByScopedId,
      final Set<String> ourResourceNames) {

    final var found = new ArrayList<IdentifierHeldElsewhere>();
    for (final var definition : definitions) {
      if (isOurs(definition.getResourceName(), ourResourceNames)) {
        continue;
      }
      final var plainBpmnProcessId = plainIdsByScopedId.get(definition.getProcessDefinitionId());
      if (plainBpmnProcessId == null) {
        // the cluster answered about an id nobody asked for, which nothing should produce -
        // reporting it would name an identifier this application does not even deploy
        continue;
      }
      found
          .add(
              new IdentifierHeldElsewhere(
                  ScopedIdentifierKind.BPMN_PROCESS_ID, plainBpmnProcessId, null, "a process definition the cluster deployed from '%s' (version %d, definition key %d)"
                      .formatted(
                          asNamedInTheMessage(definition.getResourceName()),
                          Integer.valueOf(definition.getVersion()),
                          Long.valueOf(definition.getProcessDefinitionKey())), false));
    }
    return found;

  }

  /**
   * The decision definitions the cluster holds under the decision ids of this workflow
   * module which this deployment did not bring. One search per decision id, because that
   * filter takes an exact string: it offers neither a list nor a pattern.
   */
  private static List<IdentifierHeldElsewhere> decisionIdsHeldElsewhere(
      final String adapterId,
      final String workflowModuleId,
      final String tenantId,
      final CamundaClient client,
      final Collection<DeployedDecision> decisions) {

    if ((decisions == null) || decisions.isEmpty()) {
      return List.of();
    }
    final var ourRequirements = decisions
        .stream()
        .map(DeployedDecision::decisionRequirementsId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    final var found = new ArrayList<IdentifierHeldElsewhere>();
    for (final var decision : decisions) {
      final var definitions = everyPage(
          adapterId,
          workflowModuleId,
          "decision definitions of '%s'".formatted(decision.scopedDecisionId()),
          cursor -> client
              .newDecisionDefinitionSearchRequest()
              .filter(filter -> {
                filter.decisionDefinitionId(decision.scopedDecisionId());
                if (tenantId != null) {
                  filter.tenantId(tenantId);
                }
              })
              .page(page -> {
                page.limit(Integer.valueOf(Camunda8SearchPages.PAGE_SIZE));
                if (cursor != null) {
                  page.after(cursor);
                }
              })
              .send()
              .join());
      for (final var definition : definitions) {
        if (isOurs(definition.getDmnDecisionRequirementsId(), ourRequirements)) {
          continue;
        }
        found
            .add(
                new IdentifierHeldElsewhere(
                    ScopedIdentifierKind.DMN_DECISION_ID, decision
                        .plainDecisionId(), null, "a decision definition of the decision requirements '%s' (version %d, decision key %d)"
                            .formatted(
                                asNamedInTheMessage(definition.getDmnDecisionRequirementsId()),
                                Integer.valueOf(definition.getVersion()),
                                Long.valueOf(definition.getDecisionKey())), false));
      }
    }
    return found;

  }

  /**
   * Whether what the cluster holds came out of this application's own deployment, which is
   * decided by the marker the definition carries. A definition without a marker is not
   * claimed as ours: a name the cluster cannot attribute is exactly what this check is
   * about.
   */
  private static boolean isOurs(
      final String marker,
      final Set<String> ourMarkers) {

    return (marker != null) && ourMarkers.contains(marker);

  }

  /**
   * How a marker the cluster did not report is named in the message, so a reader is not
   * left with a gap where a file name belongs.
   */
  private static String asNamedInTheMessage(
      final String marker) {

    return (marker == null) || marker.isBlank()
        ? "a resource the cluster does not name"
        : marker;

  }

  /**
   * Every item of a search, page by page, and nothing at all where the cluster did not
   * answer: this is a diagnostic, so a failed search costs a debug line and the boot goes
   * on.
   *
   * @param <T> What the search answers with
   * @param what What was asked about, for the debug line
   * @param page Runs the search from the given cursor, <code>null</code> for the first page
   * @return The items, empty where the cluster could not be asked
   */
  private static <T> List<T> everyPage(
      final String adapterId,
      final String workflowModuleId,
      final String what,
      final Function<String, SearchResponse<T>> page) {

    try {
      final var read = Camunda8SearchPages.everyPage(MAX_PAGES, page);
      if (read.theClusterHadMore()) {
        log
            .debug(
                "Camunda8[{}]: stopped after {} pages of {} while asking what the cluster already holds of "
                    + "workflow module '{}' - what was read is reported, the rest is not",
                adapterId,
                Integer.valueOf(MAX_PAGES),
                what,
                workflowModuleId);
      }
      return read.items();
    } catch (final RuntimeException e) {
      log
          .debug(
              "Camunda8[{}]: could not ask the cluster about the {} of workflow module '{}', so nothing is "
                  + "reported about them",
              adapterId,
              what,
              workflowModuleId,
              e);
      return List.of();
    }

  }

}
