package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.filter.ProcessDefinitionFilter;
import io.camunda.client.api.search.filter.ProcessInstanceFilter;
import io.camunda.client.api.search.filter.UserTaskFilter;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The three conditions a search of this adapter is built from, asked of the class an
 * EXTENSION calls - for the workflow itself and for the user tasks of it.
 * <p>
 * The one which is easy to get wrong is the variable value, which travels as the JSON the
 * cluster stores rather than as the plain id. A search built one condition differently
 * answers nothing, and nothing reads like a workflow nobody started - which is why this is
 * one class and not a rule written down twice.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SearchesTest {

  @Test
  @DisplayName("A search by aggregate id compares the quoted JSON the cluster stores")
  public void aSearchByAggregateIdIsQuoted() {

    final var filter = mock(ProcessInstanceFilter.class);

    Camunda8Searches.byAggregateId(filter, "orderId", "4711");

    final var variables = ArgumentCaptor.forClass(Map.class);
    verify(filter).variables(variables.capture());
    assertEquals(
        Map.of("orderId", "\"4711\""),
        variables.getValue(),
        "the cluster compares the variable's JSON verbatim, so the id travels with its quotes");

  }

  @Test
  @DisplayName("A search by aggregate id alone names no process and no tenant")
  public void aSearchByAggregateIdDoesNotScope() {

    final var filter = mock(ProcessInstanceFilter.class);

    Camunda8Searches.byAggregateId(filter, "orderId", "4711");

    verify(filter, never()).processDefinitionId(org.mockito.ArgumentMatchers.anyString());
    verify(filter, never()).tenantId(org.mockito.ArgumentMatchers.anyString());

  }

  @Test
  @DisplayName("A scoped search names the process the CLUSTER knows, the tenant and the quoted id")
  public void aScopedSearchNamesAllThree() {

    final var filter = mock(ProcessInstanceFilter.class);

    Camunda8Searches.scopedTo(filter, "test-module__TestProcess", "test-module", "orderId", "4711");

    verify(filter).processDefinitionId("test-module__TestProcess");
    verify(filter).tenantId("test-module");
    verify(filter).variables(Map.of("orderId", "\"4711\""));

  }

  @Test
  @DisplayName("A workflow module without a tenant is searched without one")
  public void aModuleWithoutATenantIsNotFilteredByOne() {

    final var filter = mock(ProcessInstanceFilter.class);

    Camunda8Searches.scopedTo(filter, "TestProcess", null, "orderId", "4711");

    verify(filter).processDefinitionId("TestProcess");
    verify(filter, never()).tenantId(org.mockito.ArgumentMatchers.anyString());

  }

  @Test
  @DisplayName("A definition search is the same scope without the aggregate")
  public void aDefinitionSearchCarriesNoAggregate() {

    final var filter = mock(ProcessDefinitionFilter.class);

    Camunda8Searches.scopedTo(filter, "test-module__TestProcess", "test-module");

    verify(filter).processDefinitionId("test-module__TestProcess");
    verify(filter).tenantId("test-module");

  }

  @Test
  @DisplayName("A scoped task search names the process the CLUSTER knows, the tenant and the quoted id")
  public void aScopedTaskSearchNamesAllThree() {

    final var filter = mock(UserTaskFilter.class);

    Camunda8Searches.scopedTo(filter, "test-module__TestProcess", "test-module", "orderId", "4711");

    verify(filter).bpmnProcessId("test-module__TestProcess");
    verify(filter).tenantId("test-module");
    verify(filter).processInstanceVariables(Map.of("orderId", "\"4711\""));

  }

  @Test
  @DisplayName("A task search of a module with a tenant but no rewritten process id names both as they are")
  public void aTaskSearchOfAModuleWithATenantNamesThePlainProcessId() {

    final var filter = mock(UserTaskFilter.class);

    Camunda8Searches.scopedTo(filter, "TestProcess", "test-module", "orderId", "4711");

    verify(filter).bpmnProcessId("TestProcess");
    verify(filter).tenantId("test-module");

  }

  @Test
  @DisplayName("A workflow module without a tenant has its tasks searched without one")
  public void aModuleWithoutATenantHasItsTasksSearchedWithoutOne() {

    final var filter = mock(UserTaskFilter.class);

    Camunda8Searches.scopedTo(filter, "TestProcess", null, "orderId", "4711");

    verify(filter).bpmnProcessId("TestProcess");
    verify(filter, never()).tenantId(org.mockito.ArgumentMatchers.anyString());

  }

  @Test
  @DisplayName("The task search compares the aggregate id with the value the workflow search compares")
  public void aTaskSearchComparesWhatAWorkflowSearchCompares() {

    final var workflows = mock(ProcessInstanceFilter.class);
    final var tasks = mock(UserTaskFilter.class);

    Camunda8Searches.scopedTo(workflows, "test-module__TestProcess", "test-module", "orderId",
        "47\"11");
    Camunda8Searches.scopedTo(tasks, "test-module__TestProcess", "test-module", "orderId",
        "47\"11");

    final var searchedForWorkflows = ArgumentCaptor.forClass(Map.class);
    verify(workflows).variables(searchedForWorkflows.capture());
    final var searchedForTasks = ArgumentCaptor.forClass(Map.class);
    verify(tasks).processInstanceVariables(searchedForTasks.capture());
    assertEquals(
        searchedForWorkflows.getValue(),
        searchedForTasks.getValue(),
        "the tasks of a workflow have to be found by what finds the workflow, or one of the two searches answers nothing");

  }

  @Test
  @DisplayName("Which tasks of an aggregate are meant stays with the caller")
  public void aTaskSearchLeavesTheKeyAndTheStateToTheCaller() {

    final var filter = mock(UserTaskFilter.class);

    Camunda8Searches.scopedTo(filter, "test-module__TestProcess", "test-module", "orderId", "4711");

    verify(filter, never()).userTaskKey(org.mockito.ArgumentMatchers.anyLong());
    verify(filter, never()).state(org.mockito.ArgumentMatchers.any(UserTaskState.class));

  }

}
