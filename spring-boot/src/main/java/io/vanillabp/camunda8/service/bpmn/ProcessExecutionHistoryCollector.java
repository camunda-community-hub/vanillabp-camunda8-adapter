package io.vanillabp.camunda8.service.bpmn;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ElementInstanceState;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.enums.IncidentState;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.ProcessInstance;
import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowHistory;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ProcessExecutionHistoryCollector {

    private final CamundaClient camundaClient;

    public ProcessExecutionHistoryCollector(
            final CamundaClient camundaClient) {

        this.camundaClient = camundaClient;

    }

    public WorkflowHistory collectHistory(
            final String processInstanceId,
            final String tenantId) {

        final var processInstance = loadProcessInstance(
                Long.parseLong(processInstanceId));

        final var elementInstances = loadElementInstances(
                Long.parseLong(processInstanceId), tenantId);

        final var errorByElementId = collectErrorMessages(
                Long.parseLong(processInstanceId), tenantId);

        final var callActivityChildMap = buildCallActivityChildMap(
                elementInstances, tenantId);

        final var elements = elementInstances.stream()
                .sorted(Comparator.comparing(
                        ElementInstance::getStartDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(flownode -> toElementHistory(
                        flownode, errorByElementId, callActivityChildMap))
                .collect(Collectors.toList());

        return new WorkflowHistory(
                String.valueOf(processInstance.getProcessDefinitionKey()),
                processInstance.getStartDate(),
                processInstance.getEndDate(),
                elements
        );

    }

    private ProcessInstance loadProcessInstance(
            final long processInstanceKey) {

        final var result = camundaClient
                .newProcessInstanceGetRequest(processInstanceKey)
                .send()
                .join();

        if (result == null) {
            throw new RuntimeException("Process instance not found: " + processInstanceKey);
        }
        return result;

    }

    private List<ElementInstance> loadElementInstances(
            final long processInstanceKey,
            final String tenantId) {

        return camundaClient
                .newElementInstanceSearchRequest()
                .filter(filter -> filter
                        .processInstanceKey(processInstanceKey)
                        .tenantId(tenantId))
                .sort(sort -> sort
                        .startDate()
                        .asc())
                .send()
                .join()
                .items();

    }

    private Map<String, String> collectErrorMessages(
            final long processInstanceKey,
            final String tenantId) {

        final var errors = new HashMap<String, String>();

        final var result = camundaClient
                .newIncidentSearchRequest()
                .filter(filter -> filter
                        .processInstanceKey(processInstanceKey)
                        .tenantId(tenantId)
                        .state(IncidentState.ACTIVE))
                .send()
                .join();

        for (final var incident : result.items()) {
            if (incident.getElementId() != null) {
                errors.put(
                        incident.getElementId(),
                        "%s (Incident %d)".formatted(
                                incident.getErrorMessage() != null ? incident.getErrorMessage() : "Unknown error",
                                incident.getIncidentKey()));
            }
        }

        return errors;

    }

    private Map<Long, String> buildCallActivityChildMap(
            final List<ElementInstance> elementInstances,
            final String tenantId) {

        final var result = new LinkedHashMap<Long, String>();

        final var callActivityKeys = elementInstances
                .stream()
                .filter(instance -> instance.getType().equals(ElementInstanceType.CALL_ACTIVITY))
                .map(ElementInstance::getElementInstanceKey)
                .toList();

        if (callActivityKeys.isEmpty()) {
            return result;
        }

        for (final long callActivityKey : callActivityKeys) {

            final var processInstances = camundaClient
                    .newProcessInstanceSearchRequest()
                    .filter(filter -> filter
                            .parentElementInstanceKey(callActivityKey)
                            .tenantId(tenantId))
                    .send()
                    .join()
                    .items();

            if (!processInstances.isEmpty()) {
                result.put(callActivityKey,
                        String.valueOf(processInstances.get(0).getProcessInstanceKey()));
            }

        }

        return result;
    }

    private WorkflowElementHistory toElementHistory(
            final ElementInstance elementInstance,
            final Map<String, String> errorByElementId,
            final Map<Long, String> callActivityChildMap) {

        final var secondaryContext = ElementInstanceType.CALL_ACTIVITY.equals(elementInstance.getType())
                ? callActivityChildMap.get(elementInstance.getElementInstanceKey())
                : null;

        final var isCanceled = ElementInstanceState.TERMINATED.equals(elementInstance.getState());

        return new WorkflowElementHistory(
                elementInstance.getStartDate(),
                elementInstance.getEndDate(),
                elementInstance.getElementId(),
                mapToSpi(elementInstance.getType()),
                errorByElementId.get(elementInstance.getElementId()),
                isCanceled,
                secondaryContext
        );

    }

    private WorkflowElementType mapToSpi(
            final ElementInstanceType elementInstanceType) {

        if (elementInstanceType == null) {
            return WorkflowElementType.UNKNOWN;
        }

        switch (elementInstanceType) {
            case PROCESS: return WorkflowElementType.PROCESS;
            case SUB_PROCESS: return WorkflowElementType.SUB_PROCESS;
            case EVENT_SUB_PROCESS: return WorkflowElementType.EVENT_SUB_PROCESS;
            case AD_HOC_SUB_PROCESS_INNER_INSTANCE: return WorkflowElementType.AD_HOC_SUB_PROCESS;
            case START_EVENT: return WorkflowElementType.START_EVENT;
            case INTERMEDIATE_CATCH_EVENT: return WorkflowElementType.INTERMEDIATE_CATCH_EVENT;
            case INTERMEDIATE_THROW_EVENT: return WorkflowElementType.INTERMEDIATE_THROW_EVENT;
            case BOUNDARY_EVENT: return WorkflowElementType.BOUNDARY_EVENT;
            case END_EVENT: return WorkflowElementType.END_EVENT;
            case SERVICE_TASK: return WorkflowElementType.SERVICE_TASK;
            case RECEIVE_TASK: return WorkflowElementType.RECEIVE_TASK;
            case USER_TASK: return WorkflowElementType.USER_TASK;
            case MANUAL_TASK: return WorkflowElementType.MANUAL_TASK;
            case TASK: return WorkflowElementType.TASK;
            case EXCLUSIVE_GATEWAY: return WorkflowElementType.EXCLUSIVE_GATEWAY;
            case INCLUSIVE_GATEWAY: return WorkflowElementType.INCLUSIVE_GATEWAY;
            case PARALLEL_GATEWAY: return WorkflowElementType.PARALLEL_GATEWAY;
            case EVENT_BASED_GATEWAY: return WorkflowElementType.EVENT_BASED_GATEWAY;
            case SEQUENCE_FLOW: return WorkflowElementType.SEQUENCE_FLOW;
            case MULTI_INSTANCE_BODY: return WorkflowElementType.MULTI_INSTANCE;
            case CALL_ACTIVITY: return WorkflowElementType.CALL_ACTIVITY;
            case BUSINESS_RULE_TASK: return WorkflowElementType.BUSINESS_RULE_TASK;
            case SCRIPT_TASK: return WorkflowElementType.SCRIPT_TASK;
            case SEND_TASK: return WorkflowElementType.SEND_TASK;
            // case TRANSACTION: return WorkflowElementType.TRANSACTION; // -> not yet supported by C8
            default: return WorkflowElementType.UNKNOWN;
        }

    }

}
