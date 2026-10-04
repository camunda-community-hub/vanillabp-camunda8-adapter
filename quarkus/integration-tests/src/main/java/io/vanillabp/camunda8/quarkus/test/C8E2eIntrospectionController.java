package io.vanillabp.camunda8.quarkus.test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.quarkus.runtime.VanillaBpCamunda8Properties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * What the Quarkus end-to-end tests can see of the running application: a handful of
 * <code>introspect/...</code> endpoints reporting the aggregates, the cluster's state
 * and the adapter's answers, and triggering the {@code ProcessService} operations the
 * tests want to observe.
 * <p>
 * A prod-mode test runs the application in a forked JVM, so nothing of it can be
 * injected into the test - everything travels through these endpoints. The ones
 * driving VanillaBP open their transaction themselves
 * ({@link UserTransaction}), because what happens inside the
 * caller's transaction and what only after its commit is half of what the tests are
 * about.
 */
@Path("/introspect")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class C8E2eIntrospectionController {

  private static final String ADAPTER_ID = "c8";

  static final String MODULE_ID = "c8-e2e";

  /**
   * The adapter runs with name-clash-avoidance 'use-prefix' here, so the CLUSTER knows
   * every process under its prefixed id.
   */
  static String scoped(
      final String bpmnProcessId) {

    return MODULE_ID
        + "__"
        + bpmnProcessId;

  }

  @Inject
  Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Inject
  C8E2eWorkflowService workflowService;

  @Inject
  C8PushWorkflowService pushWorkflowService;

  @Inject
  C8MessageStartWorkflowService messageStartWorkflowService;

  @Inject
  VanillaBpCamunda8Properties overlay;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory(ADAPTER_ID)
        .getClient();

  }

  // --- the application's own state ---

  @GET
  @Path("/workflow-module")
  @Produces(MediaType.TEXT_PLAIN)
  public String workflowModule() {

    return workflowService.getWorkflowModuleId();

  }

  @GET
  @Path("/aggregates/{id}")
  @Transactional
  public Map<String, Object> aggregate(
      @PathParam("id") final Long id) {

    final var aggregate = entityManager.find(C8E2eAggregate.class, id);
    final var state = new LinkedHashMap<String, Object>();
    state.put("exists", aggregate != null);
    if (aggregate != null) {
      state.put("results", aggregate.getResults());
      state.put("taskId", aggregate.getTaskId());
      state.put("approved", aggregate.isApproved());
      state.put("flat", aggregate.getFlat());
      state.put("nested", aggregate.getNested());
      state.put("inCalledProcess", aggregate.getInCalledProcess());
      state.put("bothChains", aggregate.getBothChains());
      state.put("chainOrder", aggregate.getChainOrder());
      state.put("twoLevelsDown", aggregate.getTwoLevelsDown());
    }
    return state;

  }

  @GET
  @Path("/push-aggregates/{id}")
  @Transactional
  public Map<String, Object> pushAggregate(
      @PathParam("id") final Long id) {

    final var aggregate = entityManager.find(C8PushAggregate.class, id);
    final var state = new LinkedHashMap<String, Object>();
    state.put("exists", aggregate != null);
    if (aggregate != null) {
      state.put("note", aggregate.getNote());
      state.put("taskIds", aggregate.getTaskIds());
    }
    return state;

  }

  /**
   * The aggregates of the workflows the CLUSTER started on its own - the
   * application never creates one of them.
   *
   * @return One "id|processedBy|endedAs" per aggregate
   */
  @GET
  @Path("/timer-aggregates")
  @Transactional
  public List<String> timerAggregates() {

    return entityManager
        .createQuery("select a from C8TimerAggregate a", C8TimerAggregate.class)
        .getResultList()
        .stream()
        .map(aggregate -> "%s|%s|%s".formatted(aggregate.getId(), aggregate.getProcessedBy(), aggregate.getEndedAs()))
        .toList();

  }

  /**
   * @param taskDefinition The task definition
   * @param aggregateId The aggregate
   * @return How often the cluster delivered that task
   */
  @GET
  @Path("/invocations/{taskDefinition}/{aggregateId}")
  @Produces(MediaType.TEXT_PLAIN)
  public int invocations(
      @PathParam("taskDefinition") final String taskDefinition,
      @PathParam("aggregateId") final String aggregateId) {

    return C8E2eWorkflowService.invocations(taskDefinition, aggregateId);

  }

  /**
   * @param taskDefinition The task definition
   * @param aggregateId The aggregate
   * @return The milliseconds between the first two deliveries, or -1
   */
  @GET
  @Path("/delivery-gap/{taskDefinition}/{aggregateId}")
  @Produces(MediaType.TEXT_PLAIN)
  public long deliveryGap(
      @PathParam("taskDefinition") final String taskDefinition,
      @PathParam("aggregateId") final String aggregateId) {

    return C8E2eWorkflowService.deliveryGap(taskDefinition, aggregateId);

  }

  /**
   * What the cluster handed a task as variables - the cluster's view of the aggregate.
   *
   * @return The observed variables
   */
  @GET
  @Path("/observed-variables")
  public Map<String, String> observedVariables() {

    return Map.copyOf(C8E2eWorkflowService.OBSERVED_VARIABLES);

  }

  /**
   * The job timeout and the retry backoff the adapter's overlay resolves for a scope -
   * the four levels of the configuration model, read from the real configuration.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param taskDefinition The task
   * @return The resolved durations
   */
  @GET
  @Path("/config/{workflowModuleId}/{bpmnProcessId}/{taskDefinition}")
  public Map<String, String> resolvedConfiguration(
      @PathParam("workflowModuleId") final String workflowModuleId,
      @PathParam("bpmnProcessId") final String bpmnProcessId,
      @PathParam("taskDefinition") final String taskDefinition) {

    return Map
        .of(
            "jobTimeout",
            overlay.jobTimeoutFor(workflowModuleId, bpmnProcessId, taskDefinition, ADAPTER_ID).toString(),
            "retryBackoff",
            overlay.retryBackoffFor(workflowModuleId, bpmnProcessId, taskDefinition, ADAPTER_ID).toString());

  }

  // --- starting workflows ---

  /**
   * Saves an aggregate WITHOUT starting a workflow - what an operation addressing a
   * workflow nobody started has to answer about.
   *
   * @return The aggregate's id
   */
  @POST
  @Path("/aggregates")
  @Transactional
  public Map<String, Object> seedAggregate() {

    final var aggregate = new C8E2eAggregate();
    entityManager.persist(aggregate);
    entityManager.flush();
    return Map.of("id", String.valueOf(aggregate.getId()));

  }

  /**
   * Starts the primary process through the VanillaBP user API.
   *
   * @return The aggregate's id
   */
  @POST
  @Path("/workflows")
  public Map<String, Object> startWorkflow() throws Exception {

    return startWorkflow(true);

  }

  /**
   * Starts the primary process and rolls the transaction back: neither the aggregate
   * nor the workflow may survive, because the instance is created by the phase-two
   * outbox and that outbox record is written in the caller's transaction.
   *
   * @return The aggregate's id
   */
  @POST
  @Path("/workflows/rollback")
  public Map<String, Object> startWorkflowAndRollback() throws Exception {

    return startWorkflow(false);

  }

  private Map<String, Object> startWorkflow(
      final boolean commit) throws Exception {

    userTransaction.begin();
    final String id;
    try {
      id = String.valueOf(workflowService.startWorkflow(new C8E2eAggregate()).getId());
    } catch (final Exception e) {
      userTransaction.rollback();
      return failure(e);
    }
    if (commit) {
      userTransaction.commit();
    } else {
      userTransaction.rollback();
    }
    return Map.of("id", id);

  }

  /**
   * Saves an aggregate and starts one of the workflow service's SECONDARY processes
   * against the cluster: the injectable process service starts the primary process
   * only. The aggregate-id variable is what VanillaBP finds the aggregate again by.
   *
   * @param bpmnProcessId The BPMN process to start
   * @param payloadSize The size of an additional variable no BPMN model mentions, or
   *          <code>null</code> for none
   * @return The aggregate's id and the instance key the cluster assigned
   */
  @POST
  @Path("/processes/{bpmnProcessId}")
  public Map<String, Object> startSecondaryProcess(
      @PathParam("bpmnProcessId") final String bpmnProcessId,
      @QueryParam("payloadSize") final Integer payloadSize) throws Exception {

    userTransaction.begin();
    final var aggregate = new C8E2eAggregate();
    entityManager.persist(aggregate);
    entityManager.flush();
    final var aggregateId = aggregate.getId();
    userTransaction.commit();

    final var variables = new LinkedHashMap<String, Object>();
    variables.put("id", String.valueOf(aggregateId));
    if (payloadSize != null) {
      // built here rather than sent in: a payload of that size does not fit into a URL
      variables.put("bigPayload", "x".repeat(payloadSize));
    }
    final var instanceKey = client()
        .newCreateInstanceCommand()
        .bpmnProcessId(scoped(bpmnProcessId))
        .latestVersion()
        .variables(variables)
        .send()
        .join()
        .getProcessInstanceKey();
    return Map.of("id", String.valueOf(aggregateId), "processInstanceKey", String.valueOf(instanceKey));

  }

  // --- what the application asks of VanillaBP ---

  @POST
  @Path("/tasks/{taskId}/complete/{aggregateId}")
  public Map<String, Object> completeTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.completeTask(aggregate, taskId), true);

  }

  @POST
  @Path("/tasks/{taskId}/complete-and-rollback/{aggregateId}")
  public Map<String, Object> completeTaskAndRollback(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.completeTask(aggregate, taskId), false);

  }

  @POST
  @Path("/tasks/{taskId}/cancel/{aggregateId}/{errorCode}")
  public Map<String, Object> cancelTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId,
      @PathParam("errorCode") final String errorCode) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.cancelTask(aggregate, taskId, errorCode), true);

  }

  @POST
  @Path("/user-tasks/{taskId}/complete/{aggregateId}")
  public Map<String, Object> completeUserTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.completeUserTask(aggregate, taskId), true);

  }

  @POST
  @Path("/user-tasks/{taskId}/cancel/{aggregateId}/{errorCode}")
  public Map<String, Object> cancelUserTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId,
      @PathParam("errorCode") final String errorCode) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.cancelUserTask(aggregate, taskId, errorCode), true);

  }

  @POST
  @Path("/messages/{messageName}/correlate/{aggregateId}")
  public Map<String, Object> correlateMessage(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.correlateMessage(aggregate, messageName), true);

  }

  @POST
  @Path("/messages/{messageName}/correlate/{aggregateId}/{correlationId}")
  public Map<String, Object> correlateMessage(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final Long aggregateId,
      @PathParam("correlationId") final String correlationId) throws Exception {

    return inTransaction(
        aggregateId,
        aggregate -> workflowService.correlateMessage(aggregate, messageName, correlationId),
        true);

  }

  @POST
  @Path("/messages/{messageName}/correlate-and-rollback/{aggregateId}")
  public Map<String, Object> correlateMessageAndRollback(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.correlateMessage(aggregate, messageName), false);

  }

  /**
   * Starts MessageStartProcess through its own process service.
   *
   * @param messageName The message to start it with
   * @return The id of the new aggregate
   */
  @POST
  @Path("/messages/{messageName}/start")
  @Transactional
  public Map<String, Object> startWorkflowByMessage(
      @PathParam("messageName") final String messageName) {

    final var aggregate = new C8MessageStartAggregate();
    aggregate.setId("message-start-%s".formatted(java.util.UUID.randomUUID()));
    entityManager.persist(aggregate);
    entityManager.flush();
    messageStartWorkflowService.startWorkflowByMessage(aggregate, messageName);
    return Map.of("id", aggregate.getId());

  }

  /**
   * Passes a message to the process service of TaskProcess, which may refuse it.
   *
   * @param messageName The message to start TaskProcess with
   * @return The failure, or an empty map where the start was accepted
   * @throws Exception If the transaction cannot be begun
   */
  @POST
  @Path("/messages/{messageName}/start-task-process")
  public Map<String, Object> startTaskProcessByMessage(
      @PathParam("messageName") final String messageName) throws Exception {

    userTransaction.begin();
    try {
      final var aggregate = new C8E2eAggregate();
      entityManager.persist(aggregate);
      entityManager.flush();
      workflowService.startWorkflowByMessage(aggregate, messageName);
    } catch (final Exception e) {
      userTransaction.rollback();
      return failure(e);
    }
    userTransaction.commit();
    return Map.of();

  }

  /**
   * What the workflow of MessageStartProcess recorded.
   *
   * @param id The id of its aggregate
   * @return The results, empty while there are none
   */
  @GET
  @Path("/message-start/aggregates/{id}/results")
  @Produces(MediaType.TEXT_PLAIN)
  @Transactional
  public String messageStartResults(
      @PathParam("id") final String id) {

    final var aggregate = entityManager.find(C8MessageStartAggregate.class, id);
    return (aggregate == null) || (aggregate.getResults() == null)
        ? ""
        : aggregate.getResults();

  }

  @POST
  @Path("/signals/{signalName}")
  @Transactional
  public void sendSignal(
      @PathParam("signalName") final String signalName) {

    workflowService.sendSignal(signalName);

  }

  // --- pushing a changed aggregate ---

  @POST
  @Path("/push/workflows")
  @Transactional
  public Map<String, Object> startPushWorkflow() {

    final var aggregate = new C8PushAggregate();
    aggregate.setNote("before");
    return Map.of("id", String.valueOf(pushWorkflowService.startWorkflow(aggregate).getId()));

  }

  @POST
  @Path("/push/processes/{bpmnProcessId}")
  public Map<String, Object> startPushSecondaryProcess(
      @PathParam("bpmnProcessId") final String bpmnProcessId) throws Exception {

    userTransaction.begin();
    final var aggregate = new C8PushAggregate();
    aggregate.setNote("before");
    entityManager.persist(aggregate);
    entityManager.flush();
    final var aggregateId = aggregate.getId();
    userTransaction.commit();

    final var instanceKey = client()
        .newCreateInstanceCommand()
        .bpmnProcessId(scoped(bpmnProcessId))
        .latestVersion()
        .variables(Map.of("id", String.valueOf(aggregateId), "note", "before"))
        .send()
        .join()
        .getProcessInstanceKey();
    return Map.of("id", String.valueOf(aggregateId), "processInstanceKey", String.valueOf(instanceKey));

  }

  @POST
  @Path("/push/{aggregateId}/global/{note}")
  @Transactional
  public void pushGlobally(
      @PathParam("aggregateId") final Long aggregateId,
      @PathParam("note") final String note) {

    final var aggregate = entityManager.find(C8PushAggregate.class, aggregateId);
    aggregate.setNote(note);
    pushWorkflowService.pushGlobally(aggregate);

  }

  @POST
  @Path("/push/{aggregateId}/task/{taskId}/{note}")
  @Transactional
  public void pushIntoTaskScope(
      @PathParam("aggregateId") final Long aggregateId,
      @PathParam("taskId") final String taskId,
      @PathParam("note") final String note) {

    final var aggregate = entityManager.find(C8PushAggregate.class, aggregateId);
    aggregate.setNote(note);
    pushWorkflowService.pushInto(aggregate, taskId);

  }

  // --- the viewer ---

  /**
   * The process definitions the viewer reports for a workflow, as
   * "id|bpmnProcessId|version|usedByElements".
   *
   * @param aggregateId The aggregate
   * @return One entry per definition, or the exception raised
   */
  @GET
  @Path("/viewer/definitions/{aggregateId}")
  @Transactional
  public Map<String, Object> viewerDefinitions(
      @PathParam("aggregateId") final Long aggregateId) {

    try {
      final var definitions = workflowService
          .processDefinitions(entityManager.find(C8E2eAggregate.class, aggregateId))
          .stream()
          .map(
              definition -> "%s|%s|%s|%s".formatted(
                  definition.id(),
                  definition.bpmnProcessId(),
                  definition.version(),
                  definition.usedByElements()))
          .toList();
      return Map.of("definitions", definitions);
    } catch (final Exception e) {
      return failure(e);
    }

  }

  /**
   * The definition id is handed in as a query parameter, because it carries the
   * adapter id and a '#' - which a URL path cannot.
   *
   * @param processDefinitionId The definition
   * @return The BPMN XML as deployed
   */
  @GET
  @Path("/viewer/xml")
  @Produces(MediaType.TEXT_PLAIN)
  public String viewerXml(
      @QueryParam("id") final String processDefinitionId) throws Exception {

    try (var xml = workflowService.bpmnXml(processDefinitionId)) {
      return new String(xml.readAllBytes(), StandardCharsets.UTF_8);
    }

  }

  /**
   * Asks the viewer for the XML of a process definition nobody deployed - the guiding
   * exception of the SPI.
   *
   * @param processDefinitionId The unknown definition
   * @return The exception raised
   */
  @GET
  @Path("/viewer/unknown-xml")
  public Map<String, Object> viewerUnknownXml(
      @QueryParam("id") final String processDefinitionId) {

    try (var xml = workflowService.bpmnXml(processDefinitionId)) {
      return Map.of("unexpected", xml.toString());
    } catch (final Exception e) {
      return failure(e);
    }

  }

  @GET
  @Path("/viewer/history/{aggregateId}")
  @Transactional
  public Map<String, Object> viewerHistory(
      @PathParam("aggregateId") final Long aggregateId) {

    try {
      final var history = workflowService.workflowHistory(entityManager.find(C8E2eAggregate.class, aggregateId));
      final var reported = new LinkedHashMap<String, Object>();
      reported.put("processDefinitionId", history.processDefinitionId());
      reported.put("started", history.startTime() != null);
      reported
          .put("elements", history.elementsHistory() == null
              ? null
              : history
                  .elementsHistory()
                  .stream()
                  .map(element -> element.elementId())
                  .toList());
      return reported;
    } catch (final Exception e) {
      return failure(e);
    }

  }

  // --- what the cluster holds ---

  /**
   * What the boot deployed, as "processDefinitionId|version|versionTag".
   *
   * @return One entry per deployed process definition
   */
  @GET
  @Path("/cluster/definitions")
  public List<String> clusterDefinitions() {

    return client()
        .newProcessDefinitionSearchRequest()
        .send()
        .join()
        .items()
        .stream()
        .map(
            definition -> "%s|%d|%s".formatted(
                definition.getProcessDefinitionId(),
                definition.getVersion(),
                definition.getVersionTag()))
        .sorted()
        .toList();

  }

  /**
   * The instance key of the workflow of an aggregate, read from the query API.
   *
   * @param aggregateId The aggregate
   * @return The instance key, or an empty string
   */
  @GET
  @Path("/cluster/instance-key/{aggregateId}")
  @Produces(MediaType.TEXT_PLAIN)
  public String instanceKey(
      @PathParam("aggregateId") final String aggregateId) {

    final var found = client()
        .newProcessInstanceSearchRequest()
        // variable values are stored as JSON: a String value is searched WITH its quotes
        .filter(filter -> filter.variables(Map.of("id", "\"%s\"".formatted(aggregateId))))
        .send()
        .join()
        .items();
    return found.isEmpty()
        ? ""
        : String.valueOf(found.getFirst().getProcessInstanceKey());

  }

  /**
   * The state of a workflow as the query API reports it.
   *
   * @param processInstanceKey The instance
   * @return "ACTIVE", "COMPLETED", "TERMINATED" or an empty string
   */
  @GET
  @Path("/cluster/instance-state/{processInstanceKey}")
  @Produces(MediaType.TEXT_PLAIN)
  public String instanceState(
      @PathParam("processInstanceKey") final Long processInstanceKey) {

    final var found = client()
        .newProcessInstanceSearchRequest()
        .filter(filter -> filter.processInstanceKey(processInstanceKey))
        .send()
        .join()
        .items();
    return found.isEmpty()
        ? ""
        : String.valueOf(found.getFirst().getState());

  }

  /**
   * The variables of one name of a workflow, as "scopeKey|value" - where a pushed
   * aggregate landed is read from exactly this.
   *
   * @param processInstanceKey The instance
   * @param name The variable's name
   * @return One entry per scope holding the variable
   */
  @GET
  @Path("/cluster/variables/{processInstanceKey}/{name}")
  public List<String> clusterVariables(
      @PathParam("processInstanceKey") final Long processInstanceKey,
      @PathParam("name") final String name) {

    return client()
        .newVariableSearchRequest()
        .filter(filter -> filter
            .processInstanceKey(processInstanceKey)
            .name(name))
        .send()
        .join()
        .items()
        .stream()
        .map(variable -> "%d|%s".formatted(variable.getScopeKey(), variable.getValue()))
        .toList();

  }

  /**
   * The element instance a job runs in - the scope a task-scoped push must NOT write
   * into.
   *
   * @param taskId The job key
   * @return The element instance key
   */
  @GET
  @Path("/cluster/element-instance-of-job/{taskId}")
  @Produces(MediaType.TEXT_PLAIN)
  public String elementInstanceOfJob(
      @PathParam("taskId") final Long taskId) {

    final var found = client()
        .newJobSearchRequest()
        .filter(filter -> filter.jobKey(taskId))
        .send()
        .join()
        .items();
    return found.isEmpty()
        ? ""
        : String.valueOf(found.getFirst().getElementInstanceKey());

  }

  /**
   * The element a job of the given type waits at, or nothing where the cluster holds no
   * such job. Asked with the job type as the modeller wrote it, which is what proves that
   * a connector's job type was not prefixed although the module runs under
   * {@code use-prefix}.
   *
   * @param type The job type
   * @return The element id of the first such job, or the empty string
   */
  @GET
  @Path("/cluster/element-waiting-for-job-type/{type}")
  @Produces(MediaType.TEXT_PLAIN)
  public String elementWaitingForJobType(
      @PathParam("type") final String type) {

    final var found = client()
        .newJobSearchRequest()
        .filter(filter -> filter.type(type))
        .send()
        .join()
        .items();
    return found.isEmpty()
        ? ""
        : found.getFirst().getElementId();

  }

  @POST
  @Path("/cluster/instances/{processInstanceKey}/cancel")
  public void cancelInstance(
      @PathParam("processInstanceKey") final Long processInstanceKey) {

    client()
        .newCancelInstanceCommand(processInstanceKey)
        .send()
        .join();

  }

  /**
   * Completes a job outside VanillaBP - what a concurrent completion looks like to
   * the adapter.
   *
   * @param taskId The job key
   */
  @POST
  @Path("/cluster/jobs/{taskId}/complete")
  public void completeJobInTheCluster(
      @PathParam("taskId") final Long taskId) {

    client()
        .newCompleteCommand(taskId)
        .send()
        .join();

  }

  /**
   * Deploys the second, tagged version of {@code VersionedProcess} while the
   * application runs - the way another node of a rolling deployment does it.
   */
  @POST
  @Path("/cluster/deploy-version-two")
  public void deployVersionTwo() {

    client()
        .newDeployResourceCommand()
        .addResourceFromClasspath("c8-e2e/versioned/versioned-process-v2.bpmn")
        .send()
        .join();

  }

  /**
   * Whether the query API knows the tagged second version yet - the exporter feeding
   * it runs behind the deployment.
   *
   * @return <code>true</code> once the tag arrived
   */
  @GET
  @Path("/cluster/tagged-version-known")
  @Produces(MediaType.TEXT_PLAIN)
  public boolean taggedVersionKnown() {

    return client()
        .newProcessDefinitionSearchRequest()
        .filter(filter -> filter.processDefinitionId(scoped("VersionedProcess")))
        .send()
        .join()
        .items()
        .stream()
        .anyMatch(definition -> "release-2".equals(definition.getVersionTag()));

  }

  /**
   * The user tasks of a workflow, as the query API reports them.
   *
   * @param processInstanceKey The instance
   * @return One line per user task: its key, the element it sits on and its state
   */
  @GET
  @Path("/cluster/user-tasks/{processInstanceKey}")
  public List<String> clusterUserTasks(
      @PathParam("processInstanceKey") final Long processInstanceKey) {

    return client()
        .newUserTaskSearchRequest()
        .filter(filter -> filter.processInstanceKey(processInstanceKey))
        .send()
        .join()
        .items()
        .stream()
        // the state carries the answer to a lost creation: a task standing in CREATING is
        // one whose creating listener job was never answered, and the cluster holds the
        // transition open until somebody does
        .map(task -> "%s at '%s' in state %s"
            .formatted(task.getUserTaskKey(), task.getElementId(), task.getState()))
        .toList();

  }

  /**
   * What the cluster is waiting for an operator about, for one workflow.
   * <p>
   * A listener job failed with no retry left IS an incident, so this is where a lost
   * notification ends when the delivery reached a handler and the handler said no.
   *
   * @param processInstanceKey The instance
   * @return One line per incident: its type, the element it sits on, the job it belongs
   *         to and the message an operator reads
   */
  @GET
  @Path("/cluster/incidents/{processInstanceKey}")
  public List<String> clusterIncidents(
      @PathParam("processInstanceKey") final Long processInstanceKey) {

    return client()
        .newIncidentSearchRequest()
        .filter(filter -> filter.processInstanceKey(processInstanceKey))
        .send()
        .join()
        .items()
        .stream()
        .map(incident -> "%s at '%s' (job %s) in state %s: '%s'"
            .formatted(
                incident.getErrorType(),
                incident.getElementId(),
                incident.getJobKey(),
                incident.getState(),
                inOneLine(incident.getErrorMessage())))
        .toList();

  }

  /**
   * The jobs of a workflow, as the query API reports them.
   * <p>
   * A notification which never arrives leaves one question open: was the job never
   * offered to the worker, or was it offered and lost on the way? The state answers it.
   * A job the cluster still holds as <code>CREATED</code> was activatable and nobody
   * fetched it, while a job which is gone reached somebody.
   * <p>
   * A job in state <code>FAILED</code> raises a second question: who sent the fail
   * command. The error message answers it. This adapter writes a warning first and sends
   * a one-line message. The Camunda client fails a job whose handler threw with a whole
   * stack trace as the message and one retry LESS than the job had, and it fails a job
   * its worker had no slot for with the retries unchanged and a message saying so.
   *
   * @param processInstanceKey The instance
   * @return One line per job: its key, type, state, remaining retries, lock deadline,
   *         worker and error message
   */
  @GET
  @Path("/cluster/jobs/{processInstanceKey}")
  public List<String> clusterJobs(
      @PathParam("processInstanceKey") final Long processInstanceKey) {

    return client()
        .newJobSearchRequest()
        .filter(filter -> filter.processInstanceKey(processInstanceKey))
        .send()
        .join()
        .items()
        .stream()
        .map(job -> "%d %s %s, %d retries left, locked until %s, worker '%s', error message '%s'"
            .formatted(
                job.getJobKey(),
                job.getType(),
                job.getState(),
                job.getRetries(),
                job.getDeadline(),
                job.getWorker(),
                inOneLine(job.getErrorMessage())))
        .toList();

  }

  /**
   * How many characters of an error message an introspection line carries. The error
   * message of a job the Camunda client failed is a whole stack trace, and all of it
   * would bury the rest of the line.
   */
  private static final int ERROR_MESSAGE_EXCERPT = 400;

  /**
   * An error message of the cluster as one line: the line breaks of a stack trace
   * collapsed into spaces, and only the beginning of a long one.
   *
   * @param message What the cluster reported, possibly <code>null</code>
   * @return One line, never <code>null</code>
   */
  private static String inOneLine(
      final String message) {

    if ((message == null) || message.isBlank()) {
      return "none";
    }
    final var oneLine = message.replaceAll("\\s+", " ").trim();
    return oneLine.length() <= ERROR_MESSAGE_EXCERPT
        ? oneLine
        : oneLine.substring(0, ERROR_MESSAGE_EXCERPT)
            + "... (cut after "
            + ERROR_MESSAGE_EXCERPT
            + " characters)";

  }

  // --- plumbing ---

  /**
   * Runs one {@code ProcessService} call in a transaction of its own - everything
   * reaching the cluster happens after that transaction committed, so a
   * rollback has to leave the cluster untouched.
   *
   * @param aggregateId The aggregate the operation works on
   * @param operation What to call
   * @param commit Whether to commit or to roll back
   * @return Nothing, or the exception raised
   */
  private Map<String, Object> inTransaction(
      final Long aggregateId,
      final Consumer<C8E2eAggregate> operation,
      final boolean commit) throws Exception {

    userTransaction.begin();
    try {
      operation.accept(entityManager.find(C8E2eAggregate.class, aggregateId));
    } catch (final Exception e) {
      userTransaction.rollback();
      return failure(e);
    }
    if (commit) {
      try {
        userTransaction.commit();
      } catch (final Exception e) {
        return failure(e);
      }
    } else {
      userTransaction.rollback();
    }
    return Map.of();

  }

  private Map<String, Object> failure(
      final Exception e) {

    var cause = (Throwable) e;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return Map
        .of(
            "exception",
            e
                .getClass()
                .getSimpleName(),
            "message",
            String.valueOf(e.getMessage()),
            "rootException",
            cause
                .getClass()
                .getSimpleName(),
            "rootMessage",
            String.valueOf(cause.getMessage()));

  }

}
