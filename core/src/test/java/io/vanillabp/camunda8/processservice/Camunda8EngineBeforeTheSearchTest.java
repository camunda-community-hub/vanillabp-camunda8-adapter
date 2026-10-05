package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.ModifyProcessInstanceCommandStep1;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.response.ModifyProcessInstanceResponse;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The engine answers before the search does.
 * <p>
 * Measured on three lines: the engine says "this instance exists" 16 to 19 ms after the
 * create was sent, while the search which locates a workflow by its aggregate id needs 167 to
 * 1324 ms. So where VanillaBP holds the process instance key, the election asks the engine
 * first.
 * <p>
 * The rule every test here circles: the probe may shorten the YES and nothing else. The
 * engine forgets an instance the moment it ends, so a key it does not hold covers a completed
 * workflow, a canceled one and a key which never existed alike - and only the search tells
 * those apart. A design which read the engine's 404 as "unknown" would send every ended
 * workflow of a migration setup to the wrong BPMS.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8EngineBeforeTheSearchTest {

  private static final WorkflowScope SCOPE = WorkflowScope.of("test-module", "TestProcess");

  private static final String INSTANCE_KEY = "2251799813685249";

  private static final String ANOTHER_INSTANCE_KEY = "2251799813685999";

  private final CamundaClient client = mock(CamundaClient.class);

  @Test
  @DisplayName("An instance the engine refuses the probe for is ACTIVE, and no search is sent")
  public void anInstanceTheEngineHoldsIsActiveWithoutASearch() {

    // the modification names an element no model has, so the engine refuses it - which it
    // can only do about an instance it holds, and which changes nothing about the workflow
    theEngineRefusesTheProbe(problem(400, "INVALID_ARGUMENT", "no such element"));

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY));

    verify(client, never()).newProcessInstanceSearchRequest();

  }

  @Test
  @DisplayName("An instance the engine does not hold falls through to the search")
  public void anInstanceTheEngineForgotFallsThroughToTheSearch() {

    theEngineRefusesTheProbe(new ClientHttpException("Failed with code 404", 404, "no such instance"));
    theSearchFinds(ProcessInstanceState.COMPLETED);

    assertEquals(
        WorkflowAwareness.COMPLETED,
        aService().awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY),
        "the engine forgets an instance the moment it ends, so only the search tells a "
            + "completed workflow from one which never existed");

  }

  @Test
  @DisplayName("A key no BPMS of this cluster ever had is still unknown after the search")
  public void aKeyWhichNeverExistedIsUnknown() {

    theEngineRefusesTheProbe(new ClientHttpException("Failed with code 404", 404, "no such instance"));
    theSearchFindsNothing();

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        aService().awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY),
        "which is the answer the election needs to move on to the next BPMS");

  }

  @Test
  @DisplayName("A probe which cannot answer falls through to the search unchanged")
  public void aProbeWhichCannotAnswerChangesNothing() {

    // an unreachable engine says nothing at all about whether the workflow is young, so it
    // must never be read as a 404. The search decides, which is what reports an outage
    theEngineRefusesTheProbe(new IllegalStateException("connection reset"));
    theSearchFinds(ProcessInstanceState.ACTIVE);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY));

    verify(client).newProcessInstanceSearchRequest();

  }

  @Test
  @DisplayName("Without a key the search is the whole answer")
  public void withoutAKeyNothingIsAsked() {

    theSearchFinds(ProcessInstanceState.ACTIVE);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfWorkflow(SCOPE, null, "agg-1", null));

    verify(client, never()).newModifyProcessInstanceCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("A key which is no number of this cluster is left to the search as well")
  public void aKeyOfAnotherBpmsIsLeftToTheSearch() {

    theSearchFinds(ProcessInstanceState.ACTIVE);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfWorkflow(SCOPE, null, "agg-1", "an-id-of-another-bpms"));

    verify(client, never()).newModifyProcessInstanceCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("The three-argument call is untouched and never probes")
  public void theCallWithoutAKeyIsUntouched() {

    theSearchFinds(ProcessInstanceState.ACTIVE);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfWorkflow(SCOPE, null, "agg-1"));

    verify(client, never()).newModifyProcessInstanceCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("A process whose model carries the reserved element id gets no probe")
  public void aModelCarryingTheReservedIdIsLeftAlone() {

    // an element id which by accident matches one of the model would be ACTIVATED instead
    // of refused, which is a change to a running workflow nobody asked for. The deployment
    // reads the models for it, and this is what that reading buys
    final var clientFactory = aClientFactory();
    clientFactory
        .getDeployedProcesses()
        .recordTheReservedProbeElement("test-module", "TestProcess");
    theSearchFinds(ProcessInstanceState.ACTIVE);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aServiceOf(clientFactory).awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY));

    verify(client, never()).newModifyProcessInstanceCommand(Mockito.anyLong());
    verify(client).newProcessInstanceSearchRequest();

  }

  @Test
  @DisplayName("On a cluster shared with another adapter id the engine is not asked at all")
  public void aSharedClusterIsLeftToTheSearch() {

    // an instance key is unique per CLUSTER and names no scope, and the election hands the
    // same key to every adapter of its list. Asking the engine here would answer about the
    // other adapter id's instance and end the election at the wrong adapter
    theEngineRefusesTheProbe(problem(400, "INVALID_ARGUMENT", "no such element"));
    theSearchFindsNothing();

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        aServiceOf(aClientFactoryOf(true)).awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY),
        "the search, which filters by scope, is the whole answer there");

    verify(client, never()).newModifyProcessInstanceCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("A workflow the engine has forgotten is waited for SECONDS, not for ten")
  public void theWindowFollowsWhatTheEngineAnswered() {

    final var service = aServiceOf(aClientFactoryWithWindows());
    theEngineRefusesTheProbe(new ClientHttpException("Failed with code 404", 404, "no such instance"));
    theSearchFindsNothing();

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        service.awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY));

    assertEquals(
        Duration.ofSeconds(3),
        service.workflowVisibilityDelay(INSTANCE_KEY).window(),
        "what is still on its way into the read model is the END of this workflow");
    assertEquals(
        Duration.ofSeconds(10),
        service.workflowVisibilityDelay(ANOTHER_INSTANCE_KEY).window(),
        "and another workflow, which nobody asked the engine about, keeps the long window");
    assertEquals(
        Duration.ofSeconds(10),
        service.workflowVisibilityDelay(null).window(),
        "so does a caller which holds no id at all");
    assertEquals(
        Duration.ofSeconds(10),
        service.workflowVisibilityDelay().window(),
        "and so does the question without an id, which is what an older core asks");

  }

  @Test
  @DisplayName("A probe which could not be sent leaves the long window")
  public void aProbeWhichDidNotAnswerLeavesTheLongWindow() {

    final var service = aServiceOf(aClientFactoryWithWindows());
    // an unreachable engine says nothing about whether this workflow is over, so nothing
    // here may shorten the wait
    theEngineRefusesTheProbe(new IllegalStateException("connection reset"));
    theSearchFindsNothing();

    service.awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY);

    assertEquals(Duration.ofSeconds(10), service.workflowVisibilityDelay(INSTANCE_KEY).window());

  }

  @Test
  @DisplayName("A workflow the engine still holds leaves the long window as well")
  public void aWorkflowTheEngineHoldsLeavesTheLongWindow() {

    final var service = aServiceOf(aClientFactoryWithWindows());
    theEngineRefusesTheProbe(problem(400, "INVALID_ARGUMENT", "no such element"));

    assertEquals(
        WorkflowAwareness.ACTIVE,
        service.awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY));

    assertEquals(
        Duration.ofSeconds(10),
        service.workflowVisibilityDelay(INSTANCE_KEY).window(),
        "the engine holding it says nothing about an end, and the core does not wait here anyway");

  }

  private Camunda8ProcessService<?> aService() {

    return aServiceOf(aClientFactory());

  }

  private Camunda8ProcessService<?> aServiceOf(
      final Camunda8ClientFactory clientFactory) {

    return new Camunda8ProcessService<Object>(
        "c8", clientFactory, Duration.ofDays(14), (
            aggregateClass,
            check) -> check.run(), null);

  }

  private Camunda8ClientFactory aClientFactory() {

    return aClientFactoryOf(false);

  }

  /**
   * A cluster with both windows configured, which is what the defaults look like: ten
   * seconds for a workflow which may just have been started, three for one the engine has
   * already forgotten.
   */
  private Camunda8ClientFactory aClientFactoryWithWindows() {

    final var clientFactory = aClientFactoryOf(false);
    clientFactory.getConfiguration().setWorkflowVisibilityTimeout(Duration.ofSeconds(10));
    clientFactory.getConfiguration().setEndedWorkflowVisibilityTimeout(Duration.ofSeconds(3));
    return clientFactory;

  }

  private Camunda8ClientFactory aClientFactoryOf(
      final boolean shared) {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing ever contacts - every request of this test meets the mock above
    configuration.setRestAddress("http://localhost:1");
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    return new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

      @Override
      public boolean sharesItsCluster() {
        return shared;
      }

    };

  }

  private void theEngineRefusesTheProbe(
      final RuntimeException rejection) {

    final var step3 = mock(
        ModifyProcessInstanceCommandStep1.ModifyProcessInstanceCommandStep3.class,
        RETURNS_SELF);
    Mockito.lenient().when(step3.send()).thenThrow(rejection);
    final var step1 = mock(ModifyProcessInstanceCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(step1.activateElement(Mockito.anyString())).thenReturn(step3);
    Mockito.lenient().when(client.newModifyProcessInstanceCommand(Mockito.anyLong())).thenReturn(step1);

  }

  /**
   * Answers the probe rather than refusing it, which is what an element id matching one of
   * the model would produce - the case the deployment check keeps away from here.
   */
  @Test
  @DisplayName("An instance whose probe the engine TOOK is ACTIVE as well")
  public void anInstanceWhoseProbeWasTakenIsActive() {

    final var step3 = mock(
        ModifyProcessInstanceCommandStep1.ModifyProcessInstanceCommandStep3.class,
        RETURNS_SELF);
    @SuppressWarnings("unchecked")
    final CamundaFuture<ModifyProcessInstanceResponse> answer = mock(CamundaFuture.class);
    Mockito.lenient().when(answer.join()).thenReturn(null);
    Mockito.lenient().when(step3.send()).thenReturn(answer);
    final var step1 = mock(ModifyProcessInstanceCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(step1.activateElement(Mockito.anyString())).thenReturn(step3);
    Mockito.lenient().when(client.newModifyProcessInstanceCommand(Mockito.anyLong())).thenReturn(step1);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfWorkflow(SCOPE, null, "agg-1", INSTANCE_KEY));

  }

  private void theSearchFinds(
      final ProcessInstanceState state) {

    final var instance = mock(ProcessInstance.class);
    Mockito.lenient().when(instance.getState()).thenReturn(state);
    Mockito.lenient().when(instance.getTenantId()).thenReturn("<default>");
    Mockito.lenient().when(instance.getProcessDefinitionId()).thenReturn("TestProcess");
    theSearchAnswers(List.of(instance));

  }

  private void theSearchFindsNothing() {

    theSearchAnswers(List.of());

  }

  private void theSearchAnswers(
      final List<ProcessInstance> instances) {

    final var search = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    @SuppressWarnings("unchecked")
    final SearchResponse<ProcessInstance> found = mock(SearchResponse.class);
    Mockito.lenient().when(found.items()).thenReturn(instances);
    @SuppressWarnings("unchecked")
    final CamundaFuture<SearchResponse<ProcessInstance>> answer = mock(CamundaFuture.class);
    Mockito.lenient().when(answer.join()).thenReturn(found);
    Mockito.lenient().when(search.send()).thenReturn(answer);
    Mockito.lenient().when(client.newProcessInstanceSearchRequest()).thenReturn(search);

  }

  private static ProblemException problem(
      final int status,
      final String title,
      final String reason) {

    final var details = new ProblemDetail();
    details.setStatus(status);
    details.setTitle(title);
    return new ProblemException(status, reason, details);

  }

}
