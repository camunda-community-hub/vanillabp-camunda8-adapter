package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.search.request.ElementInstanceSearchRequest;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessDefinition;

/**
 * What the viewer reports where {@code name-clash-avoidance: use-prefix} gives every process id
 * a prefix in the cluster.
 * <p>
 * The deployed models carry the prefixed ids, the call activities included, while the viewer
 * reports the ids the application wrote. So an id read out of a model or out of the cluster is
 * turned back into the plain one before it is looked up or reported.
 * <p>
 * The cluster is played by mocks.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ViewerUnderAPrefixTest {

  private static final String MODULE = "orders";

  /**
   * How this test prefixes an id, standing in for the core's name-clash avoidance.
   */
  private static String prefixed(
      final String bpmnProcessId) {

    return "%s__%s".formatted(MODULE, bpmnProcessId);

  }

  private static final String PARENT_BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
      xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_Parent" \
      targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%s" isExecutable="true">
          <bpmn:startEvent id="TheStart" />
          <bpmn:callActivity id="TheCallActivity">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="%s" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(prefixed("ParentProcess"), prefixed("SubProcess"));

  private static final String SUB_BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_Sub" \
      targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%s" isExecutable="true">
          <bpmn:startEvent id="TheSubStart" />
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(prefixed("SubProcess"));

  private final CamundaClient client = mock(CamundaClient.class);

  private List<ProcessInstance> foundInstances = List.of();

  private Camunda8WorkflowViewer viewer;

  @BeforeEach
  public void setUp() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:1");
    final var clientFactory = spy(new Camunda8ClientFactory("c8", configuration));
    doReturn(client)
        .when(clientFactory)
        .getClient();
    // the deployment records the PLAIN id next to the model as deployed, which is prefixed
    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                MODULE, "ParentProcess", "111", 3, Bpmn
                    .readModelFromStream(new ByteArrayInputStream(PARENT_BPMN.getBytes(StandardCharsets.UTF_8)))));
    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                MODULE, "SubProcess", "222", 1, Bpmn
                    .readModelFromStream(new ByteArrayInputStream(SUB_BPMN.getBytes(StandardCharsets.UTF_8)))));

    final var instanceSearch = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newProcessInstanceSearchRequest()).thenReturn(instanceSearch);
    when(instanceSearch.send()).thenAnswer(invocation -> future(response(foundInstances)));
    final var elementSearch = mock(ElementInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newElementInstanceSearchRequest()).thenReturn(elementSearch);
    when(elementSearch.send()).thenAnswer(invocation -> future(response(List.of())));

    viewer = new Camunda8WorkflowViewer("c8", clientFactory, (
        module,
        process) -> prefixed(process), module -> null);

  }

  @Test
  @DisplayName("The process behind a call activity is found although the model names it with its prefix")
  public void theCalledProcessIsFoundUnderItsPlainId() {

    // nothing running, so the definitions of the deployed version answer
    final var definitions = viewer.getProcessDefinitions(MODULE, "ParentProcess", "id", 1, null);

    assertEquals(
        List.of(
            new ProcessDefinition("111", "ParentProcess", "3", null),
            new ProcessDefinition("222", "SubProcess", "1", List.of("TheCallActivity"))),
        definitions,
        "the call activity names '%s', and the deployment knows the process as 'SubProcess'"
            .formatted(prefixed("SubProcess")));

  }

  @Test
  @DisplayName("A definition only the cluster still holds is reported under its plain id as well")
  public void aDefinitionOfAPreviousVersionIsReportedUnderItsPlainId() {

    // a workflow which runs on a definition an earlier version of the application deployed,
    // so only the cluster knows it, and under its prefixed id
    final var instance = mock(ProcessInstance.class);
    when(instance.getProcessInstanceKey()).thenReturn(4711L);
    when(instance.getProcessDefinitionKey()).thenReturn(999L);
    when(instance.getProcessDefinitionId()).thenReturn(prefixed("ParentProcess"));
    when(instance.getProcessDefinitionVersion()).thenReturn(2);
    foundInstances = List.of(instance);

    final var definitions = viewer.getProcessDefinitions(MODULE, "ParentProcess", "id", 1, null);

    assertEquals(
        "ParentProcess",
        definitions.getFirst().bpmnProcessId(),
        "the deployed version and an earlier one name the same process the same way");

  }

  private static <T> SearchResponse<T> response(
      final List<T> items) {

    @SuppressWarnings("unchecked")
    final SearchResponse<T> response = mock(SearchResponse.class);
    when(response.items()).thenReturn(items);
    return response;

  }

  private static <T> CamundaFuture<T> future(
      final T value) {

    @SuppressWarnings("unchecked")
    final CamundaFuture<T> future = mock(CamundaFuture.class);
    when(future.join()).thenReturn(value);
    return future;

  }

}
