package io.vanillabp.camunda8.processservice;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import io.camunda.client.api.search.enums.ElementInstanceState;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.enums.IncidentState;
import io.camunda.client.api.search.filter.ProcessInstanceFilter;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.CallActivity;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledElement;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8QueryApi;
import io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowHistory;
import lombok.extern.slf4j.Slf4j;

/**
 * The Camunda 8 part of VanillaBP's viewer/history API.
 * <p>
 * <b>Two data sources, deliberately:</b>
 * <ol>
 * <li><b>What this application version deployed</b>
 * ({@link Camunda8DeployedProcesses}) - definitions and BPMN XML are served from
 * the models VanillaBP's deployment pipeline reads at EVERY boot. No cluster
 * round trip, no eventual consistency, and no search.</li>
 * <li><b>The cluster's query API</b> - which version a
 * RUNNING workflow actually uses, the workflow timeline, and definitions deployed
 * by PREVIOUS application versions (a long-running workflow surviving a
 * redeployment). A cluster which does not ANSWER costs the second source and nothing
 * else: the definitions of the currently deployed version are reported and the element
 * history is <code>null</code> (the SPI's documented "not supported by the
 * underlying BPMS"), never an error.</li>
 * </ol>
 * <b>Eventual consistency:</b> the query API lags behind the engine by design. A
 * workflow started moments ago may not be visible yet - this is reported as
 * "no history (yet)", never as a failure. Viewers polling shortly after will see
 * it.
 * <p>
 * The <b>history context</b> of this adapter is the called instance's PROCESS
 * INSTANCE KEY (of a call activity), and the <b>adapter-native definition id</b>
 * is the process definition key.
 */
@Slf4j
public class Camunda8WorkflowViewer {

  private final String adapterId;

  private final Camunda8ClientFactory clientFactory;

  /**
   * Maps (workflow module ID, plain BPMN process ID) to the process definition id the
   * CLUSTER knows - the name-clash-avoidance mode may prefix it. Queries
   * against the cluster have to use the scoped id, while everything served from the
   * deployment record stays plain.
   */
  private final BinaryOperator<String> scopedProcessId;

  /**
   * The tenant a workflow module's instances live in, or <code>null</code> if the mode
   * uses none. Queries have to be restricted to it: without a tenant filter another
   * module deploying the same BPMN process id could be reported.
   */
  private final UnaryOperator<String> tenantIdOf;

  /**
   * Whether a viewer query which the cluster did not answer was already reported for this
   * adapter id - the rest of them is a DEBUG line, so an outage costs one warning rather
   * than one per read.
   */
  private final AtomicBoolean viewerQueryFailureWarned = new AtomicBoolean();

  /**
   * Builds the viewer of one configured adapter id, which the process service of that id owns.
   * <p>
   * Written out rather than generated, because javadoc does not run Lombok: a generated
   * constructor is missing from the published documentation, which then advertises a no-arg
   * constructor this class has never had.
   *
   * @param adapterId The configured adapter id this viewer answers for
   * @param clientFactory The clients of that adapter id
   * @param scopedProcessId Turns a workflow module id and a plain BPMN process id into the id
   *          the cluster knows
   * @param tenantIdOf The tenant a workflow module's instances live in, or <code>null</code>
   *          where the mode uses none
   */
  public Camunda8WorkflowViewer(
      final String adapterId,
      final Camunda8ClientFactory clientFactory,
      final BinaryOperator<String> scopedProcessId,
      final UnaryOperator<String> tenantIdOf) {

    this.adapterId = adapterId;
    this.clientFactory = clientFactory;
    this.scopedProcessId = scopedProcessId;
    this.tenantIdOf = tenantIdOf;

  }

  /**
   * The process definitions of the addressed (sub-)workflow.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The BPMN process id of the primary process
   * @param aggregateIdName The name of the process variable holding the aggregate ID
   * @param workflowAggregateId The workflow aggregate's ID
   * @param historyContext <code>null</code> or a called instance's process
   *        instance key
   * @return The definitions or an EMPTY list if the workflow is unknown here
   */
  public List<ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String aggregateIdName,
      final Object workflowAggregateId,
      final String historyContext) {

    final var instance = findInstance(
        workflowModuleId, bpmnProcessId, aggregateIdName, workflowAggregateId, historyContext);

    final Definition definition;
    if (instance != null) {
      definition = definitionOf(instance);
    } else if (historyContext != null) {
      // a context which cannot be resolved (the cluster did not answer, or the
      // instance is gone) must not silently answer with the primary process's definitions
      return List.of();
    } else {
      definition = deployedDefinition(workflowModuleId, bpmnProcessId);
    }
    if (definition == null) {
      return List.of();
    }

    final var definitions = new ArrayList<ProcessDefinition>();
    definitions.add(
        new ProcessDefinition(
            definition.processDefinitionKey(), definition.bpmnProcessId(), String.valueOf(definition.version()), null));
    definitions.addAll(
        calledDefinitions(workflowModuleId, definition));
    return definitions;

  }

  /**
   * The BPMN XML of a process definition: from what this application version
   * deployed or - for definitions of previous application versions - from the
   * cluster.
   *
   * @param processDefinitionId The Camunda 8 process definition key
   * @return The XML or <code>null</code> if unknown
   */
  public InputStream getBpmnXml(
      final String processDefinitionId) {

    final var deployed = clientFactory
        .getDeployedProcesses()
        .byDefinitionKey(processDefinitionId);
    if (deployed != null) {
      return toInputStream(Bpmn.convertToString(deployed.model()));
    }

    // deployed by a PREVIOUS application version: only the cluster still has it
    try {
      final var xml = clientFactory
          .getClient()
          .newProcessDefinitionGetXmlRequest(Long.parseLong(processDefinitionId))
          .send()
          .join();
      return xml == null
          ? null
          : toInputStream(xml);
    } catch (final NumberFormatException e) {
      return null;
    } catch (final Exception e) {
      log.info(
          "Camunda8[{}]: the process definition '{}' was not deployed by this application version "
              + "and the cluster did not answer for it, so only the definitions of the RUNNING "
              + "application version are available until it does ({})",
          adapterId,
          processDefinitionId,
          Camunda8QueryApi.WHY_A_SEARCH_FAILS_AFTER_THE_DEPLOYMENT,
          e);
      return null;
    }

  }

  /**
   * The execution history of the addressed (sub-)workflow.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The BPMN process id of the primary process
   * @param aggregateIdName The name of the process variable holding the aggregate ID
   * @param workflowAggregateId The workflow aggregate's ID
   * @param historyContext <code>null</code> or a called instance's process
   *        instance key
   * @return The history or <code>null</code> if the workflow is unknown here
   */
  public WorkflowHistory getWorkflowHistory(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String aggregateIdName,
      final Object workflowAggregateId,
      final String historyContext) {

    final var instance = findInstance(
        workflowModuleId, bpmnProcessId, aggregateIdName, workflowAggregateId, historyContext);
    if (instance == null) {
      if (historyContext != null) {
        return null;
      }
      // the workflow is not visible yet, or the cluster did not answer: report the
      // definition which would be executed - the element history is unavailable, which
      // the SPI expresses as null (NOT an error, see the class comment)
      final var deployed = deployedDefinition(workflowModuleId, bpmnProcessId);
      return deployed == null
          ? null
          : new WorkflowHistory(deployed.processDefinitionKey(), null, null, null);
    }

    final var elementInstances = elementInstancesOf(instance.getProcessInstanceKey());
    if (elementInstances == null) {
      return new WorkflowHistory(
          String.valueOf(instance.getProcessDefinitionKey()), instance.getStartDate(), instance.getEndDate(), null);
    }

    final var incidentMessages = openIncidentMessagesByElement(instance.getProcessInstanceKey());
    final var elements = elementInstances
        .stream()
        .map(elementInstance -> new WorkflowElementHistory(
            elementInstance.getStartDate(), elementInstance.getEndDate(), elementInstance.getElementId(), elementTypeOf(
                elementInstance.getType()), incidentMessages.get(elementInstance.getElementId()), elementInstance
                    .getState() == ElementInstanceState.TERMINATED, calledInstanceKeyOf(
                        elementInstance)))
        .toList();

    return new WorkflowHistory(
        String.valueOf(instance.getProcessDefinitionKey()), instance.getStartDate(), instance.getEndDate(), elements);

  }

  /**
   * The definition a (sub-)workflow runs on, plus the model needed to determine
   * its call activities.
   *
   * @param processDefinitionKey The adapter-native definition id
   * @param bpmnProcessId The BPMN process id
   * @param version The version
   * @param model The BPMN model, or <code>null</code> where a definition of a previous
   *        application version could not be read from the cluster
   */
  private record Definition(
                            String processDefinitionKey,
                            String bpmnProcessId,
                            int version,
                            BpmnModelInstance model) {
  }

  private Definition definitionOf(
      final ProcessInstance instance) {

    final var processDefinitionKey = String.valueOf(instance.getProcessDefinitionKey());
    final var deployed = clientFactory
        .getDeployedProcesses()
        .byDefinitionKey(processDefinitionKey);
    if (deployed != null) {
      return new Definition(
          processDefinitionKey, deployed.bpmnProcessId(), deployed.version(), deployed.model());
    }

    // running on a definition of a PREVIOUS application version: read its XML
    // from the cluster so the call activities stay resolvable
    BpmnModelInstance model = null;
    try (var xml = getBpmnXml(processDefinitionKey)) {
      if (xml != null) {
        model = Bpmn.readModelFromStream(xml);
      }
    } catch (final Exception e) {
      log.debug(
          "Camunda8[{}]: could not read the BPMN of process definition '{}' from the cluster",
          adapterId,
          processDefinitionKey,
          e);
    }
    return new Definition(
        processDefinitionKey, instance.getProcessDefinitionId(), instance.getProcessDefinitionVersion() == null
            ? 0
            : instance.getProcessDefinitionVersion(), model);

  }

  private Definition deployedDefinition(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var deployed = clientFactory
        .getDeployedProcesses()
        .deployedVersionOf(workflowModuleId, bpmnProcessId);
    return deployed == null
        ? null
        : new Definition(
            deployed.processDefinitionKey(), deployed.bpmnProcessId(), deployed.version(), deployed.model());

  }

  /**
   * The definitions called by the call activities of the given definition, in the
   * version which WOULD be executed next: the version deployed by this
   * application. Call activities addressing their process by a FEEL expression
   * are skipped - the called definition is only known at execution time.
   * <p>
   * So an empty answer means one of two things, and a reader cannot tell them apart: this
   * definition calls nothing, or every call activity of it names its process by an
   * expression. Nothing says which, because a view is rendered without an instance running
   * and there is no expression to evaluate. A task of such a called process IS told the
   * iteration it runs in, which the caller hands it at runtime, but that is a job's knowledge
   * and not a definition's.
   */
  private List<ProcessDefinition> calledDefinitions(
      final String workflowModuleId,
      final Definition definition) {

    if (definition.model() == null) {
      return List.of();
    }

    final var elementsByCalledProcess = new LinkedHashMap<String, List<String>>();
    for (final var callActivity : definition
        .model()
        .getModelElementsByType(CallActivity.class)) {
      final var calledProcessId = calledProcessIdOf(callActivity);
      if (calledProcessId == null) {
        continue;
      }
      elementsByCalledProcess
          .computeIfAbsent(calledProcessId, key -> new ArrayList<>())
          .add(callActivity.getId());
    }

    final var definitions = new ArrayList<ProcessDefinition>();
    elementsByCalledProcess.forEach((
        calledProcessId,
        elementIds) -> {
      final var deployed = clientFactory
          .getDeployedProcesses()
          .deployedVersionOf(workflowModuleId, calledProcessId);
      if (deployed == null) {
        log.debug(
            "Camunda8[{}]: the called process '{}' of workflow module '{}' was not deployed by "
                + "this application version - the call activities {} are not reported",
            adapterId,
            calledProcessId,
            workflowModuleId,
            elementIds);
        return;
      }
      definitions.add(
          new ProcessDefinition(
              deployed.processDefinitionKey(), deployed.bpmnProcessId(), String.valueOf(deployed.version()), List
                  .copyOf(elementIds)));
    });
    return definitions;

  }

  /**
   * Reads {@code zeebe:calledElement processId} of a call activity - a static
   * process id only; an expression ({@code =...}) is not resolvable here, so such a call
   * activity is left out of the view rather than guessed at.
   */
  private static String calledProcessIdOf(
      final CallActivity callActivity) {

    final var calledElement = callActivity.getSingleExtensionElement(ZeebeCalledElement.class);
    if (calledElement == null) {
      return null;
    }
    final var processId = calledElement.getProcessId();
    if ((processId == null) || processId.isBlank() || processId.startsWith("=")) {
      return null;
    }
    return processId;

  }

  /**
   * Finds the process instance addressed: the workflow's primary instance (by the
   * aggregate-ID variable) or - for a history context - the called instance,
   * accepted ONLY if its root instance is that very workflow.
   *
   * @return The instance or <code>null</code> (unknown, not visible yet, or the cluster
   *         did not answer)
   */
  private ProcessInstance findInstance(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String aggregateIdName,
      final Object workflowAggregateId,
      final String historyContext) {

    final var tenantId = tenantIdOf.apply(workflowModuleId);
    final var primaryInstance = searchOne(
        filter -> Camunda8Searches
            .scopedTo(
                filter,
                scopedProcessId.apply(workflowModuleId, bpmnProcessId),
                tenantId,
                aggregateIdName,
                workflowAggregateId));
    if (historyContext == null) {
      return primaryInstance;
    }
    if (primaryInstance == null) {
      return null;
    }

    final long calledInstanceKey;
    try {
      calledInstanceKey = Long.parseLong(historyContext);
    } catch (final NumberFormatException e) {
      return null;
    }
    final var calledInstance = searchOne(filter -> filter.processInstanceKey(calledInstanceKey));
    if (calledInstance == null) {
      return null;
    }
    if (!belongsTo(calledInstance, primaryInstance.getProcessInstanceKey())) {
      log.warn(
          "Camunda8[{}]: the history context '{}' does not belong to the workflow of aggregate "
              + "'{}' (BPMN process '{}', workflow module '{}') - ignoring it",
          adapterId,
          historyContext,
          workflowAggregateId,
          bpmnProcessId,
          workflowModuleId);
      return null;
    }
    return calledInstance;

  }

  /**
   * Whether the given instance is (a descendant of) the workflow's primary
   * instance - the parent chain is walked with a sane bound.
   */
  private boolean belongsTo(
      final ProcessInstance instance,
      final Long primaryInstanceKey) {

    var current = instance;
    for (var level = 0; level < 20; ++level) {
      if (primaryInstanceKey.equals(current.getProcessInstanceKey())) {
        return true;
      }
      final var parentKey = current.getParentProcessInstanceKey();
      if (parentKey == null) {
        return false;
      }
      current = searchOne(filter -> filter.processInstanceKey(parentKey));
      if (current == null) {
        return false;
      }
    }
    return false;

  }

  private ProcessInstance searchOne(
      final Consumer<ProcessInstanceFilter> filter) {

    try {
      return clientFactory
          .getClient()
          .newProcessInstanceSearchRequest()
          .filter(filter)
          .send()
          .join()
          .items()
          .stream()
          .findFirst()
          .orElse(null);
    } catch (final Exception e) {
      theClusterDidNotAnswerTheViewer(e, "process instances");
      return null;
    }

  }

  /**
   * The history of one workflow is every element instance it has, not the first page of
   * them: a multi-instance or a loop takes one workflow past the 100 a search hands out
   * without being asked, and a history cut off there looks exactly like a complete one.
   *
   * @return The element instances in execution order, or <code>null</code> where the
   *         cluster did not answer - which the SPI reads as "this BPMS serves no
   *         history", never as an error
   */
  private List<ElementInstance> elementInstancesOf(
      final Long processInstanceKey) {

    try {
      final var read = Camunda8SearchPages
          .everyPage(
              cursor -> clientFactory
                  .getClient()
                  .newElementInstanceSearchRequest()
                  .filter(filter -> filter.processInstanceKey(processInstanceKey))
                  .sort(sort -> sort
                      .startDate()
                      .asc())
                  .page(page -> {
                    page.limit(Integer.valueOf(Camunda8SearchPages.PAGE_SIZE));
                    if (cursor != null) {
                      page.after(cursor);
                    }
                  })
                  .send()
                  .join());
      if (read.theClusterHadMore()) {
        // the history record has no way of saying "and there was more", so the log is
        // where it is said - a reader of the returned history cannot tell
        log.warn(
            "Camunda8[{}]: the history of process instance '{}' is reported with the first {} "
                + "element instances, which is where reading stopped, so it shows the beginning of "
                + "that workflow and not all of it",
            adapterId,
            processInstanceKey,
            Integer.valueOf(read.items().size()));
      }
      return read.items();
    } catch (final Exception e) {
      theClusterDidNotAnswerTheViewer(e, "element instances");
      return null;
    }

  }

  private Map<String, String> openIncidentMessagesByElement(
      final Long processInstanceKey) {

    final var messages = new HashMap<String, String>();
    try {
      // paged for the same reason the element instances are: the iterations of a
      // multi-instance fail one incident each, and a message missing from the history
      // reads as an element which ran cleanly
      Camunda8SearchPages
          .everyPage(
              cursor -> clientFactory
                  .getClient()
                  .newIncidentsByProcessInstanceSearchRequest(processInstanceKey)
                  .page(page -> {
                    page.limit(Integer.valueOf(Camunda8SearchPages.PAGE_SIZE));
                    if (cursor != null) {
                      page.after(cursor);
                    }
                  })
                  .send()
                  .join())
          .items()
          .forEach(incident -> {
            if (incident.getState() == IncidentState.ACTIVE) {
              messages.putIfAbsent(incident.getElementId(), incident.getErrorMessage());
            }
          });
    } catch (final Exception e) {
      log.debug(
          "Camunda8[{}]: incidents of process instance '{}' are unavailable - the history is "
              + "reported without error messages",
          adapterId,
          processInstanceKey,
          e);
    }
    return messages;

  }

  /**
   * The called instance's key of a call activity element - the SPI's secondary
   * history context to dig into the sub-process.
   */
  private String calledInstanceKeyOf(
      final ElementInstance elementInstance) {

    if (elementInstance.getType() != ElementInstanceType.CALL_ACTIVITY) {
      return null;
    }
    final var elementInstanceKey = elementInstance.getElementInstanceKey();
    final var calledInstance = searchOne(
        filter -> filter.parentElementInstanceKey(elementInstanceKey));
    return calledInstance == null
        ? null
        : String.valueOf(calledInstance.getProcessInstanceKey());

  }

  /**
   * One WARN per adapter id for a viewer query the cluster did not answer.
   * <p>
   * This says outage and nothing else. Whether the cluster CAN be searched was settled
   * while the adapter deployed, and one which cannot never got that far, so there is no
   * missing capability left for a failed query here to assert - which it must not do
   * anyway: the cluster answers the same 403 for a refused endpoint and for a request the
   * credentials may not make, and only the probe of {@link Camunda8QueryApi} tells those
   * apart. The credentials are named because one which loses its read permission while
   * the application runs arrives here and nowhere else.
   */
  private void theClusterDidNotAnswerTheViewer(
      final Exception exception,
      final String subject) {

    if (viewerQueryFailureWarned.compareAndSet(false, true)) {
      log.warn(
          "Camunda8[{}]: the viewer/history API could not query {}, so the element history is "
              + "reported as unavailable ('no history', never an error) and process definitions "
              + "are served from what this application version deployed. Retried on the next "
              + "call - unless the cluster stopped answering searches for good ({}).",
          adapterId,
          subject,
          Camunda8QueryApi.WHY_A_SEARCH_FAILS_AFTER_THE_DEPLOYMENT,
          exception);
    } else {
      log.debug(
          "Camunda8[{}]: the viewer/history API could not query {}",
          adapterId,
          subject,
          exception);
    }

  }

  private static InputStream toInputStream(
      final String xml) {

    return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));

  }

  /**
   * Maps Camunda 8's element instance types onto the SPI's element types.
   */
  static WorkflowElementType elementTypeOf(
      final ElementInstanceType type) {

    if (type == null) {
      return WorkflowElementType.UNKNOWN;
    }
    return switch (type) {
      case PROCESS -> WorkflowElementType.PROCESS;
      case SUB_PROCESS -> WorkflowElementType.SUB_PROCESS;
      case EVENT_SUB_PROCESS -> WorkflowElementType.EVENT_SUB_PROCESS;
      case AD_HOC_SUB_PROCESS, AD_HOC_SUB_PROCESS_INNER_INSTANCE -> WorkflowElementType.AD_HOC_SUB_PROCESS;
      case START_EVENT -> WorkflowElementType.START_EVENT;
      case INTERMEDIATE_CATCH_EVENT -> WorkflowElementType.INTERMEDIATE_CATCH_EVENT;
      case INTERMEDIATE_THROW_EVENT -> WorkflowElementType.INTERMEDIATE_THROW_EVENT;
      case BOUNDARY_EVENT -> WorkflowElementType.BOUNDARY_EVENT;
      case END_EVENT -> WorkflowElementType.END_EVENT;
      case SERVICE_TASK -> WorkflowElementType.SERVICE_TASK;
      case RECEIVE_TASK -> WorkflowElementType.RECEIVE_TASK;
      case USER_TASK -> WorkflowElementType.USER_TASK;
      case MANUAL_TASK -> WorkflowElementType.MANUAL_TASK;
      case TASK -> WorkflowElementType.TASK;
      case EXCLUSIVE_GATEWAY -> WorkflowElementType.EXCLUSIVE_GATEWAY;
      case INCLUSIVE_GATEWAY -> WorkflowElementType.INCLUSIVE_GATEWAY;
      case PARALLEL_GATEWAY -> WorkflowElementType.PARALLEL_GATEWAY;
      case EVENT_BASED_GATEWAY -> WorkflowElementType.EVENT_BASED_GATEWAY;
      case SEQUENCE_FLOW -> WorkflowElementType.SEQUENCE_FLOW;
      case MULTI_INSTANCE_BODY -> WorkflowElementType.MULTI_INSTANCE;
      case CALL_ACTIVITY -> WorkflowElementType.CALL_ACTIVITY;
      case BUSINESS_RULE_TASK -> WorkflowElementType.BUSINESS_RULE_TASK;
      case SCRIPT_TASK -> WorkflowElementType.SCRIPT_TASK;
      case SEND_TASK -> WorkflowElementType.SEND_TASK;
      default -> WorkflowElementType.UNKNOWN;
    };

  }

}
