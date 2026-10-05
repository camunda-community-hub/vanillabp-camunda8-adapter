package io.vanillabp.camunda8.deployment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessDefinitionState;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.filter.ProcessDefinitionFilter;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.processservice.Camunda8SearchPages;
import io.vanillabp.camunda8.processservice.Camunda8Searches;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.version.CachingProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import lombok.extern.slf4j.Slf4j;

/**
 * The versions of the process definitions of ONE Camunda 8 cluster (= one adapter id):
 * what the core matches <code>&#64;WorkflowTask(version = ...)</code> and its siblings
 * against.
 * <p>
 * The version itself travels with every job ({@code ActivatedJob#getProcessDefinitionVersion}),
 * so nothing here is needed for version specifications made of numbers. Version TAGS
 * are a different matter: a job does not carry one, and which version carries which tag
 * can only be read by searching the cluster - one of the reasons this adapter requires a
 * cluster it can search, see decision 20 in the repository's DECISIONS.md.
 * <p>
 * What the deployment reported is recorded without any query
 * ({@link #recordDeployed(String, String, int, String)}): the deploy command
 * names the version the cluster assigned, and the model carries its
 * {@code zeebe:versionTag}.
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class Camunda8ProcessVersions extends CachingProcessVersionCatalog {

  private final String adapterId;

  private final Supplier<CamundaClient> client;

  /**
   * The BPMN process id as the CLUSTER knows it for a (workflow module, plain BPMN
   * process id) - the identifiers may be prefixed.
   */
  private final BiFunction<String, String, String> scopedProcessIds;

  /**
   * The tenant a workflow module is deployed to, or <code>null</code>.
   */
  private final Function<String, String> tenants;

  /**
   * The cluster's process definition key per (workflow module, BPMN process, version).
   * <p>
   * The startup check for old versions asks two things about every version older than the
   * one this boot deployed, its model and how many workflows still run on it, and both are
   * addressed by this key. Searching for it per question meant three searches per version
   * where one already held the answer: {@link #fetchDeployedVersions} reads the definitions
   * and used to keep nothing but their version numbers. So it keeps the keys as well, and
   * the search below runs only for a version deployed after that list was read - see
   * decision 13 in the repository's DECISIONS.md.
   * <p>
   * A key stays here for the life of the application, so a version deleted while it runs
   * is still answered from here. Nothing is invalidated for that: the questions these
   * keys serve are asked while an application boots, and the boot after the deletion
   * reads the list again.
   */
  private final Map<String, Long> definitionKeysByVersion = new ConcurrentHashMap<>();

  /**
   * Reads the tasks of a model the cluster holds - the deployment service' own
   * extraction.
   */
  @FunctionalInterface
  public interface TasksOfModel {

    /**
     * Reads the tasks out of one model.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The PLAIN BPMN process ID
     * @param version The version the cluster assigned
     * @param model The model as the cluster runs it
     * @return The tasks the model declares
     */
    Collection<BpmnTaskSpec> of(
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        BpmnModelInstance model);

  }

  private TasksOfModel tasksOfModel;

  /**
   * Hands over how a model is read for tasks. The deployment service does this after both
   * halves are built, because each one needs the other.
   *
   * @param tasksOfModel How the deployment service reads a model
   */
  public void setTasksOfModel(
      final TasksOfModel tasksOfModel) {

    this.tasksOfModel = tasksOfModel;

  }

  /**
   * Reads one kind of finding out of a model the cluster holds - the walk the deployment
   * service already runs over the model it deploys, run over a model it never brought.
   *
   * @param <T> What the walk finds
   */
  @FunctionalInterface
  public interface WhatAModelDeclares<T> {

    /**
     * Runs the walk over one model.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The PLAIN BPMN process ID
     * @param model The model as the cluster runs it
     * @return What the walk found, empty where the model declares none of it
     */
    Collection<T> of(
        String workflowModuleId,
        String bpmnProcessId,
        BpmnModelInstance model);

  }

  /**
   * The model of ONE version the cluster holds, out of the picture every check judging a
   * model asks - see decision 21 in the repository's DECISIONS.md.
   */
  @FunctionalInterface
  public interface HeldModelOfVersion {

    /**
     * Reads the model of one version off the cluster.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The PLAIN BPMN process ID
     * @param version The version the cluster assigned
     * @return The model, or <code>null</code> where the cluster could not be asked or
     *         does not hold that version any more
     */
    BpmnModelInstance of(
        String workflowModuleId,
        String bpmnProcessId,
        String version);

  }

  private HeldModelOfVersion heldModelOfVersion;

  private WhatAModelDeclares<BpmsInitiatedStartSpec> startEventsOfModel;

  /**
   * Hands over how a model the cluster holds is fetched, for the same reason
   * {@link #setTasksOfModel(TasksOfModel)} is handed over rather than passed in.
   *
   * @param heldModelOfVersion How the deployment service gets at a model the cluster
   *          holds
   */
  public void setHeldModelOfVersion(
      final HeldModelOfVersion heldModelOfVersion) {

    this.heldModelOfVersion = heldModelOfVersion;

  }

  /**
   * Hands over how the start events of a model are read, for the same reason
   * {@link #setTasksOfModel(TasksOfModel)} is handed over rather than passed in.
   *
   * @param startEventsOfModel How the deployment service reads the start events a model
   *          declares
   */
  public void setStartEventsOfModel(
      final WhatAModelDeclares<BpmsInitiatedStartSpec> startEventsOfModel) {

    this.startEventsOfModel = startEventsOfModel;

  }

  @Override
  public Collection<BpmsInitiatedStartSpec> startEventsOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return whatTheHeldModelDeclares(workflowModuleId, bpmnProcessId, version, startEventsOfModel);

  }

  private WhatAModelDeclares<String> concurrentTokenElementsOfModel;

  /**
   * Hands over how the elements which can put a second token into a workflow are read, for the
   * same reason {@link #setTasksOfModel(TasksOfModel)} is handed over rather than passed in.
   *
   * @param concurrentTokenElementsOfModel How the deployment service reads the elements
   *          which can put a second token into a workflow of a model
   */
  public void setConcurrentTokenElementsOfModel(
      final WhatAModelDeclares<String> concurrentTokenElementsOfModel) {

    this.concurrentTokenElementsOfModel = concurrentTokenElementsOfModel;

  }

  @Override
  public Collection<String> concurrentTokenElementsOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return whatTheHeldModelDeclares(
        workflowModuleId,
        bpmnProcessId,
        version,
        concurrentTokenElementsOfModel);

  }

  /**
   * What one version's model declares, or that this adapter cannot say.
   * <p>
   * The model comes from the picture of what the cluster holds rather than from a read of
   * its own, so the answers this adapter gives about a held version and the checks it makes
   * against one see the same models, read once (decision 21 in the repository's
   * DECISIONS.md). Where the picture cannot tell, and where the cluster does not hold that
   * version any more, the answer is <code>null</code>: both are a model nobody read, and
   * an empty answer would claim that the model has no such element.
   */
  private <T> Collection<T> whatTheHeldModelDeclares(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final WhatAModelDeclares<T> walk) {

    if ((walk == null) || (heldModelOfVersion == null)) {
      return null;
    }
    final BpmnModelInstance model;
    try {
      model = heldModelOfVersion.of(workflowModuleId, bpmnProcessId, version);
    } catch (final RuntimeException e) {
      log.warn(
          "Camunda8[{}]: the model of version {} of BPMN process '{}' (workflow module '{}') could not be read, "
              + "so VanillaBP says nothing about that version",
          adapterId,
          version,
          bpmnProcessId,
          workflowModuleId,
          e);
      return null;
    }
    return model == null
        ? null
        : walk.of(workflowModuleId, bpmnProcessId, model);

  }

  @Override
  public Collection<BpmnTaskSpec> tasksOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    if (tasksOfModel == null) {
      return null;
    }
    final var definitionKey = definitionKeyOf(workflowModuleId, bpmnProcessId, version);
    if (definitionKey == null) {
      // the cluster does not hold that version any more, which is the only way there is
      // no key: a cluster answering no search at all never gets the adapter this far.
      // The core says once that this BPMS cannot tell
      return null;
    }
    try {
      final var model = modelOfTheVersionInTurn(workflowModuleId, bpmnProcessId, version, definitionKey);
      if (model == null) {
        return List.of();
      }
      return tasksOfModel.of(workflowModuleId, bpmnProcessId, version, model);
    } catch (final RuntimeException e) {
      log.warn(
          "Camunda8[{}]: the model of version {} of BPMN process '{}' (workflow module '{}') could not be read, "
              + "so VanillaBP cannot tell whether this application still serves it",
          adapterId,
          version,
          bpmnProcessId,
          workflowModuleId,
          e);
      return null;
    }

  }

  /**
   * Reads the identifiers a model the cluster holds declares - the deployment service's own
   * reading, so the names of a held version and the names of the model just deployed are
   * read the same way. The pair of {@link TasksOfModel}: both are answered out of ONE model,
   * which is what lets them share a fetch.
   */
  @FunctionalInterface
  public interface IdentifiersOfModel {

    /**
     * Reads the identifiers out of one model.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The PLAIN BPMN process ID
     * @param version The version the cluster assigned
     * @param model The model as the cluster runs it
     * @return The identifiers the model declares
     */
    Collection<ModelIdentifier> of(
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        BpmnModelInstance model);

  }

  private IdentifiersOfModel identifiersOfModel;

  /**
   * Hands over how the identifiers of a model are read, for the same reason
   * {@link #setTasksOfModel(TasksOfModel)} is handed over rather than passed in.
   *
   * @param identifiersOfModel How the deployment service reads the identifiers a model
   *          declares
   */
  public void setIdentifiersOfModel(
      final IdentifiersOfModel identifiersOfModel) {

    this.identifiersOfModel = identifiersOfModel;

  }

  @Override
  public Collection<ModelIdentifier> identifiersOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    if (identifiersOfModel == null) {
      return null;
    }
    final var definitionKey = definitionKeyOf(workflowModuleId, bpmnProcessId, version);
    if (definitionKey == null) {
      return null;
    }
    try {
      final var model = modelOfTheVersionInTurn(workflowModuleId, bpmnProcessId, version, definitionKey);
      if (model == null) {
        return List.of();
      }
      return identifiersOfModel.of(workflowModuleId, bpmnProcessId, version, model);
    } catch (final RuntimeException e) {
      log.warn(
          "Camunda8[{}]: the model of version {} of BPMN process '{}' (workflow module '{}') could not be read, "
              + "so VanillaBP says nothing about the names that version still declares",
          adapterId,
          version,
          bpmnProcessId,
          workflowModuleId,
          e);
      return null;
    }

  }

  /**
   * The model of the version somebody is asking about right now.
   *
   * @param definitionKey The cluster's process definition key of that version
   * @return The model, or <code>null</code> where the cluster answered nothing for the key
   */
  private BpmnModelInstance modelOfTheVersionInTurn(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final long definitionKey) {

    final var key = versionKey(workflowModuleId, bpmnProcessId, version);
    final var inTurn = modelInTurn;
    if ((inTurn != null) && inTurn.key().equals(key)) {
      return inTurn.model();
    }
    // a race here costs one fetch and nothing else, which is why this is a plain
    // assignment: the questions it serves are put while the application boots
    final var model = readModel(definitionKey);
    modelInTurn = new ModelInTurn(key, model);
    return model;

  }

  /**
   * The model of ONE version, held for as long as that version's turn lasts.
   * <p>
   * The startup check over the versions the cluster still holds asks several questions
   * about one version before it moves on to the next, and reading a model here means
   * fetching its XML over the wire - so asking the model twice for one version would pay
   * for it twice. What bounds the lifetime is the next version's question, which replaces
   * the entry: one model per adapter id at most, never one per version, and nothing at
   * runtime reads any of it.
   *
   * @param key The version this model belongs to
   * @param model The model, <code>null</code> where the cluster answered nothing
   */
  private record ModelInTurn(
                             String key,
                             BpmnModelInstance model) {
  }

  private volatile ModelInTurn modelInTurn;

  @Override
  public Long activeInstanceCountOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    final var definitionKey = definitionKeyOf(workflowModuleId, bpmnProcessId, version);
    if (definitionKey == null) {
      return null;
    }
    // the TOTAL, not the page: a search answers one page of items, so counting what
    // came back would cap every answer at the page size and quietly turn "5000 still
    // run on this version" into the page size. One item is fetched because the count
    // is what is wanted, not the instances
    final var found = client
        .get()
        .newProcessInstanceSearchRequest()
        .filter(filter -> filter
            .state(ProcessInstanceState.ACTIVE)
            .processDefinitionKey(definitionKey))
        .page(page -> page.limit(1))
        .send()
        .join();
    return found.page().totalItems();

  }

  /**
   * The model of one definition, read out of the cluster.
   *
   * @param definitionKey The cluster's process definition key
   * @return The model, or <code>null</code> where the cluster answered nothing for
   *         the key
   */
  private BpmnModelInstance readModel(
      final long definitionKey) {

    final var xml = client.get().newProcessDefinitionGetXmlRequest(definitionKey).send().join();
    if (xml == null) {
      return null;
    }
    return Bpmn
        .readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * The versions the cluster still holds under one BPMN process id, oldest first -
   * read fresh rather than from the resolution cache, because the caller keeps the
   * answer itself and a swallowed failure would make an empty cluster look like an
   * unreachable one.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The version numbers, oldest first
   * @throws RuntimeException If the cluster could not be asked
   */
  public List<String> versionsHeldUnder(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return fetchDeployedVersions(workflowModuleId, bpmnProcessId)
        .stream()
        .map(DeployedProcessVersion::version)
        .toList();

  }

  /**
   * The model of one version the cluster holds.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version the cluster assigned
   * @return The model, or <code>null</code> where the cluster does not hold that
   *         version any more
   * @throws RuntimeException If the cluster could not be asked
   */
  public BpmnModelInstance modelOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    final var definitionKey = definitionKeyOf(workflowModuleId, bpmnProcessId, version);
    if (definitionKey == null) {
      return null;
    }
    return readModel(definitionKey);

  }

  /**
   * The cluster's process definition key of ONE version of a process - the handle both
   * the model and the instance count are read by. From what the version list already
   * brought back, and only otherwise from a search of its own.
   */
  private Long definitionKeyOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    if (!version.matches("\\d+")) {
      return null;
    }
    final var known = definitionKeysByVersion.get(versionKey(workflowModuleId, bpmnProcessId, version));
    if (known != null) {
      return known;
    }
    return askTheClusterForTheDefinitionKey(workflowModuleId, bpmnProcessId, version);

  }

  private static String versionKey(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, version);

  }

  /**
   * Keeps what a definition search brought back, so the next question about the same
   * version is answered without searching again.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param definition What the cluster reported
   * @return The definition key of that version
   */
  private Long remember(
      final String workflowModuleId,
      final String bpmnProcessId,
      final ProcessDefinition definition) {

    final var definitionKey = definition.getProcessDefinitionKey();
    definitionKeysByVersion
        .put(
            versionKey(workflowModuleId, bpmnProcessId, String.valueOf(definition.getVersion())),
            definitionKey);
    return definitionKey;

  }

  /**
   * Which process definitions this adapter counts as existing, applied to every search
   * it runs for them.
   * <p>
   * Read by every definition search of this package, the one which asks what the cluster
   * already holds of a workflow module's identifiers included, so the answer to "which
   * definitions count" cannot differ between them.
   * <p>
   * A definition an operator deleted stays in the query API and is answered with the
   * state <code>DELETED</code>, so a search leaving this out reports versions the
   * cluster runs nothing on any more - and the startup check would keep demanding
   * methods for them, restart after restart, with deleting the definition being the one
   * remedy an operator has already tried. See decision 15 in the repository's
   * DECISIONS.md.
   *
   * @param filter The filter of a process definition search
   */
  static void onlyDefinitionsWhichStillCount(
      final ProcessDefinitionFilter filter) {

    filter.state(ProcessDefinitionState.ACTIVE);

  }

  private Long askTheClusterForTheDefinitionKey(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    final var scopedProcessId = scopedProcessIds.apply(workflowModuleId, bpmnProcessId);
    final var tenantId = tenants.apply(workflowModuleId);
    return client
        .get()
        .newProcessDefinitionSearchRequest()
        .filter(filter -> {
          onlyDefinitionsWhichStillCount(filter);
          Camunda8Searches.scopedTo(filter, scopedProcessId, tenantId);
          filter.version(Integer.valueOf(version));
        })
        // one process, one tenant, one version: the cluster holds a single definition
        // under those three, so one entry is the whole answer
        .page(page -> page.limit(Integer.valueOf(1)))
        .send()
        .join()
        .items()
        .stream()
        .findFirst()
        .map(definition -> remember(workflowModuleId, bpmnProcessId, definition))
        .orElse(null);

  }

  /**
   * Builds the version reader of one adapter id. The client arrives as a supplier because the
   * deployment service builds this before the client exists.
   *
   * @param adapterId The adapter id whose cluster is asked
   * @param client Where the client of that id comes from
   * @param scopedProcessIds Turns a workflow module id and a plain BPMN process id into the id
   *          the cluster knows
   * @param tenants The tenant a workflow module's instances live in, or <code>null</code> where
   *          the mode uses none
   */
  public Camunda8ProcessVersions(
      final String adapterId,
      final Supplier<CamundaClient> client,
      final BiFunction<String, String, String> scopedProcessIds,
      final Function<String, String> tenants) {

    this.adapterId = adapterId;
    this.client = client;
    this.scopedProcessIds = scopedProcessIds;
    this.tenants = tenants;

  }

  /**
   * Remembers a version the deploy command reported.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version the cluster assigned
   * @param versionTag The <code>zeebe:versionTag</code> of the model or
   *          <code>null</code>
   */
  public void recordDeployed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final int version,
      final String versionTag) {

    record(workflowModuleId, bpmnProcessId, DeployedProcessVersion.of(String.valueOf(version), versionTag));

  }

  /**
   * The version this boot deployed, per process id as the CLUSTER knows it - which is
   * the id an activated job carries, so a job worker can compare without translating
   * anything.
   */
  private final Map<String, Integer> deployedVersionByScopedProcessId = new ConcurrentHashMap<>();

  /**
   * Remembers which version this boot deployed for a process, under the id the cluster
   * uses.
   *
   * @param scopedBpmnProcessId The process id as the cluster knows it
   * @param version The version the cluster assigned
   */
  public void recordDeployedScoped(
      final String scopedBpmnProcessId,
      final int version) {

    deployedVersionByScopedProcessId.put(scopedBpmnProcessId, version);

  }

  /**
   * Whether a workflow running on the given version of the given process was started
   * before the version this boot deployed.
   * <p>
   * Answers <code>false</code> where this boot deployed nothing for that process, which
   * is the honest answer of a node that only opened workers: it cannot know where the
   * boundary is, and a lower bound claimed without knowing would be worse than the exact
   * number it replaces.
   *
   * @param scopedBpmnProcessId The process id as the cluster knows it
   * @param version The version a job reported
   * @return <code>true</code> where the version is older than the deployed one
   */
  public boolean predatesDeployedVersion(
      final String scopedBpmnProcessId,
      final int version) {

    final var deployed = deployedVersionByScopedProcessId.get(scopedBpmnProcessId);
    return (deployed != null) && (version < deployed);

  }

  @Override
  protected List<DeployedProcessVersion> fetchDeployedVersions(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var scopedProcessId = scopedProcessIds.apply(workflowModuleId, bpmnProcessId);
    final var tenantId = tenants.apply(workflowModuleId);
    // every version the cluster holds is the answer here, not the first page of them: a
    // process redeployed with every release of an application passes 100 versions, and the
    // page a search hands out without being asked holds 100. Newest first, so the bound of
    // the paging can only cut versions nobody asks about any more - the questions put to
    // this list are about the versions around the one just deployed
    final var read = Camunda8SearchPages
        .everyPage(
            cursor -> client
                .get()
                .newProcessDefinitionSearchRequest()
                .filter(filter -> {
                  onlyDefinitionsWhichStillCount(filter);
                  Camunda8Searches.scopedTo(filter, scopedProcessId, tenantId);
                })
                .sort(sort -> sort.version().desc())
                .page(page -> {
                  page.limit(Integer.valueOf(Camunda8SearchPages.PAGE_SIZE));
                  if (cursor != null) {
                    page.after(cursor);
                  }
                })
                .send()
                .join());
    final var definitions = read.items();
    if (read.theClusterHadMore()) {
      log
          .warn(
              "Camunda8[{}]: read {} versions of BPMN process '{}' (workflow module '{}') and stopped there, "
                  + "so VanillaBP says nothing about versions older than those. The newest ones are the ones "
                  + "it was asked about, so this only shows where a cluster keeps every version a release ever "
                  + "deployed - deleting the definitions nothing runs on any more ends it",
              adapterId,
              Integer.valueOf(definitions.size()),
              bpmnProcessId,
              workflowModuleId);
    }
    // this one search holds what every later question about an older version needs, and
    // keeping the keys is what spares those questions a search each
    definitions.forEach(definition -> remember(workflowModuleId, bpmnProcessId, definition));
    // the catalog is answered oldest first, and the search ran the other way round
    return definitions
        .stream()
        .map(definition -> DeployedProcessVersion
            .of(String.valueOf(definition.getVersion()), definition.getVersionTag()))
        .toList()
        .reversed();

  }

  @Override
  public String whatOlderVersionsMiss(
      final String workflowModuleId,
      final String bpmnProcessId) {

    // this adapter brings VanillaBP's behaviour by writing into the model it deploys,
    // and a running workflow stays on the version it was started on - so everything
    // listed here reaches the version deployed now and no earlier one. Camunda 7
    // answers nothing to the same question, because it attaches while the engine parses
    // a definition, which reaches every version the engine holds
    return "the end of a workflow is not reported to a @WorkflowEnded method, user-task lifecycle "
        + "notifications do not arrive where the listeners were added by this deployment, and a "
        + "message catch event correlates only by a correlation key its own model already carried";

  }

}
