package io.vanillabp.camunda8.processservice;

import java.util.Map;

import io.camunda.client.api.search.filter.ProcessDefinitionFilter;
import io.camunda.client.api.search.filter.ProcessInstanceFilter;
import io.camunda.client.api.search.filter.UserTaskFilter;

/**
 * The filters this adapter searches its cluster with, in one place.
 * <p>
 * A search for a workflow always has the same three conditions: the BPMN process id AS THE
 * CLUSTER KNOWS IT, the tenant the workflow module runs under, and the id of the workflow
 * aggregate, as the JSON in which the cluster stores a variable. Each of the three is a rule
 * of this adapter, not of the Camunda client. Name-clash avoidance may have rewritten the
 * process id. The tenant may be absent. And the variable value carries quotes (see
 * {@link Camunda8VariableFilters}).
 * <p>
 * The client spells those conditions differently, depending on what is searched for. A
 * process-instance search calls the process id {@code processDefinitionId}. A user-task
 * search calls it {@code bpmnProcessId}. The aggregate id is a variable of the search in the
 * one case, and a variable of the process instance in the other. The conditions are the
 * same, so they are written here and not at every caller.
 * <p>
 * It is public, because an extension which searches the same cluster for the same workflow
 * has to ask the same question. If one condition is built differently, the search does not
 * fail. It answers nothing, and that looks exactly like a workflow which was never started.
 * Everything here only ADDS conditions to the filter it is handed. So a caller who needs a
 * narrower search calls the client again after it.
 */
public final class Camunda8Searches {

  private Camunda8Searches() {
  }

  /**
   * Narrows a process-instance search to the workflow of one aggregate, over every process
   * and every tenant the cluster holds.
   * <p>
   * That is the search of an awareness probe. A probe is asked about a workflow, not about a
   * process. On a cluster which two adapter ids share, it may find the instance of the other
   * id as well. The caller sorts those out. That is why this does not scope.
   *
   * @param filter The filter of the search request
   * @param aggregateIdVariableName The name of the variable the aggregate's id travels in.
   *          It is named after the id attribute of the aggregate, so it comes from the
   *          persistence of the call at hand
   * @param workflowAggregateId The aggregate's id
   */
  public static void byAggregateId(
      final ProcessInstanceFilter filter,
      final String aggregateIdVariableName,
      final Object workflowAggregateId) {

    filter.variables(aggregateIdCondition(aggregateIdVariableName, workflowAggregateId));

  }

  /**
   * Narrows a process-instance search to the workflow of one aggregate in ONE process of
   * ONE tenant. It is for a caller which knows which process it is asking about.
   *
   * @param filter The filter of the search request
   * @param scopedBpmnProcessId The BPMN process id as the CLUSTER knows it, which is what
   *          {@code NameClashAvoidanceSupport#scopedProcessId} answers
   * @param tenantId The tenant of the workflow module, or <code>null</code> where the
   *          module uses none
   * @param aggregateIdVariableName The name of the variable the aggregate's id travels in
   * @param workflowAggregateId The aggregate's id
   */
  public static void scopedTo(
      final ProcessInstanceFilter filter,
      final String scopedBpmnProcessId,
      final String tenantId,
      final String aggregateIdVariableName,
      final Object workflowAggregateId) {

    filter.processDefinitionId(scopedBpmnProcessId);
    if (tenantId != null) {
      filter.tenantId(tenantId);
    }
    byAggregateId(filter, aggregateIdVariableName, workflowAggregateId);

  }

  /**
   * Narrows a process-DEFINITION search to one process of one tenant. It is the same scope
   * without the aggregate, because a definition belongs to no workflow.
   *
   * @param filter The filter of the search request
   * @param scopedBpmnProcessId The BPMN process id as the CLUSTER knows it
   * @param tenantId The tenant of the workflow module, or <code>null</code> where the
   *          module uses none
   */
  public static void scopedTo(
      final ProcessDefinitionFilter filter,
      final String scopedBpmnProcessId,
      final String tenantId) {

    filter.processDefinitionId(scopedBpmnProcessId);
    if (tenantId != null) {
      filter.tenantId(tenantId);
    }

  }

  /**
   * Narrows a user-task search to the tasks of one aggregate in ONE process of ONE tenant.
   * It is the scope of a search for the workflow itself, spelled the way a user-task search
   * spells it.
   * <p>
   * The aggregate id is compared as a variable of the PROCESS INSTANCE. VanillaBP writes it
   * to the workflow, and a task holds no copy of it. So a local-variable condition would
   * answer nothing. In a search built through here, that condition also means that a guessed
   * task key reads no task of another case. It is not a check on anybody's rights, and this
   * method cannot make it one. It only sets conditions on the filter it is handed. Whoever
   * holds the client can ask it anything without ever coming past here.
   * <p>
   * The caller decides which tasks of that aggregate it means: one task by its key, or only
   * the tasks somebody can still work on. It asks the client for that after this.
   *
   * @param filter The filter of the search request
   * @param scopedBpmnProcessId The BPMN process id as the CLUSTER knows it, which is what
   *          {@code NameClashAvoidanceSupport#scopedProcessId} answers
   * @param tenantId The tenant of the workflow module, or <code>null</code> where the
   *          module uses none
   * @param aggregateIdVariableName The name of the variable the aggregate's id travels in
   * @param workflowAggregateId The aggregate's id
   */
  public static void scopedTo(
      final UserTaskFilter filter,
      final String scopedBpmnProcessId,
      final String tenantId,
      final String aggregateIdVariableName,
      final Object workflowAggregateId) {

    filter.bpmnProcessId(scopedBpmnProcessId);
    if (tenantId != null) {
      filter.tenantId(tenantId);
    }
    filter
        .processInstanceVariables(
            aggregateIdCondition(aggregateIdVariableName, workflowAggregateId));

  }

  /**
   * The condition every search of this class compares the aggregate's id with.
   * <p>
   * One expression for all of them, because a search for the tasks of a workflow has to
   * compare the same as a search for the workflow itself. Written twice, it drifts one day,
   * and then one of the two answers nothing.
   */
  private static Map<String, Object> aggregateIdCondition(
      final String aggregateIdVariableName,
      final Object workflowAggregateId) {

    return Map
        .of(
            aggregateIdVariableName,
            Camunda8VariableFilters.aggregateIdSearchValue(workflowAggregateId));

  }

}
