package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.PublishMessageCommandStep1;
import io.camunda.client.api.command.PublishMessageCommandStep1.PublishMessageCommandStep2;
import io.camunda.client.api.command.PublishMessageCommandStep1.PublishMessageCommandStep3;
import io.camunda.client.api.command.SetVariablesCommandStep1;
import io.camunda.client.api.command.SetVariablesCommandStep1.SetVariablesCommandStep2;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The process service of a workflow reaches into a called process as well: a message the
 * called process waits for, and a push of the changed aggregate.
 * <p>
 * The operations are called on the process service of the process at the top, so the request
 * names that process. What waits in a called process belongs to the called process, though. A
 * time-to-live configured for a message is read under the process whose model waits for it, and
 * a push reaches the called instances which continue the aggregate.
 * <p>
 * The cluster is played by mocks.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SettingsOfACalledProcessTest {

  private static final String MODULE = "called-settings-module";

  private static final String CALLER = "SettingsCaller";

  private static final String CHILD = "SettingsChild";

  private static final String OTHER_CHILD = "SettingsOtherChild";

  private static final String MESSAGE = "childHello";

  private static final long CALLER_INSTANCE = 2251799813690126L;

  private static final long CHILD_INSTANCE = 2251799813690200L;

  private static final long GRANDCHILD_INSTANCE = 2251799813690300L;

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * The processes the time-to-live was asked for, in order.
   */
  private final List<String> processesAskedForTheTimeToLive = new ArrayList<>();

  /**
   * Which process instances the changed aggregate was written to.
   */
  private final List<Long> writtenTo = new ArrayList<>();

  private Camunda8ProcessService<Object> processService;

  private Camunda8ClientFactory clientFactory;

  @BeforeEach
  public void setUp() {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing contacts: every request of this test meets a mock
    configuration.setRestAddress("http://localhost:1");
    clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

    };
    processService = new Camunda8ProcessService<>("c8", clientFactory, Duration.ofDays(14), (
        aggregateClass,
        check) -> check.run(), null);
    processService
        .setMessageTimeToLiveResolver((
            workflowModuleId,
            bpmnProcessId,
            messageName) -> {
          processesAskedForTheTimeToLive.add(bpmnProcessId);
          return Duration.ofMinutes(5);
        });

  }

  @Test
  @DisplayName("A message the called process waits for gets the time-to-live of the called process")
  public void theTimeToLiveIsReadForTheProcessWhichWaits() {

    deployed(CALLER, waitingFor(CALLER, null));
    deployed(CHILD, waitingFor(CHILD, MESSAGE));
    publishesAreAccepted();

    correlate();

    assertEquals(List.of(CHILD), processesAskedForTheTimeToLive);

  }

  @Test
  @DisplayName("Where the process of the call waits for the message itself, its time-to-live counts")
  public void theProcessOfTheCallWinsWhereItWaitsItself() {

    deployed(CALLER, waitingFor(CALLER, MESSAGE));
    deployed(CHILD, waitingFor(CHILD, MESSAGE));
    publishesAreAccepted();

    correlate();

    assertEquals(List.of(CALLER), processesAskedForTheTimeToLive);

  }

  @Test
  @DisplayName("Where two other processes wait for the message, the process of the call stays the answer")
  public void twoOtherProcessesLeaveTheProcessOfTheCall() {

    deployed(CALLER, waitingFor(CALLER, null));
    deployed(CHILD, waitingFor(CHILD, MESSAGE));
    deployed(OTHER_CHILD, waitingFor(OTHER_CHILD, MESSAGE));
    publishesAreAccepted();

    correlate();

    assertEquals(List.of(CALLER), processesAskedForTheTimeToLive);

  }

  @Test
  @DisplayName("A push without a task reaches the called instances which continue the aggregate")
  public void aPushWithoutATaskReachesTheCalledInstances() {

    writesAreRecorded();
    // the read model knows one called instance below the caller, and one below that
    calledInstancesAre(List.of(List.of(CHILD_INSTANCE), List.of(GRANDCHILD_INSTANCE), List.of()));

    processService
        .phaseOperations()
        .get(PhaseOperation.AGGREGATE_CHANGED)
        .phaseTwo(
            new PhaseTwoRequest<>(
                MODULE, CALLER, persistence(), "56", Map.of(), null, String.valueOf(CALLER_INSTANCE)));

    assertEquals(
        List.of(CALLER_INSTANCE, CHILD_INSTANCE, GRANDCHILD_INSTANCE),
        writtenTo,
        "a called instance got a copy of the caller's variables at the call and nothing since, so it has to be"
            + " written itself");

  }

  private void correlate() {

    processService
        .phaseOperations()
        .get(PhaseOperation.CORRELATE_MESSAGE)
        .phaseTwo(
            new PhaseTwoRequest<>(
                MODULE, CALLER, persistence(), "56", Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, MESSAGE)));

  }

  private void deployed(
      final String processId,
      final String bpmn) {

    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                MODULE, processId, String.valueOf(processId.hashCode()), 1, Bpmn
                    .readModelFromStream(new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)))));

  }

  /**
   * @param processId The process
   * @param messageName The message its intermediate catch event waits for, or
   *          <code>null</code> for a process which waits for nothing
   * @return A BPMN file
   */
  private static String waitingFor(
      final String processId,
      final String messageName) {

    final var waits = messageName == null
        ? """
            <bpmn:sequenceFlow id="ToTheEnd" sourceRef="Start" targetRef="End" />
            <bpmn:endEvent id="End"><bpmn:incoming>ToTheEnd</bpmn:incoming></bpmn:endEvent>
            """
        : """
            <bpmn:sequenceFlow id="ToTheWait" sourceRef="Start" targetRef="Wait" />
            <bpmn:intermediateCatchEvent id="Wait">
              <bpmn:incoming>ToTheWait</bpmn:incoming><bpmn:outgoing>ToTheEnd</bpmn:outgoing>
              <bpmn:messageEventDefinition messageRef="TheMessage" />
            </bpmn:intermediateCatchEvent>
            <bpmn:sequenceFlow id="ToTheEnd" sourceRef="Wait" targetRef="End" />
            <bpmn:endEvent id="End"><bpmn:incoming>ToTheEnd</bpmn:incoming></bpmn:endEvent>
            """;
    final var message = messageName == null
        ? ""
        : """
            <bpmn:message id="TheMessage" name="%s">
              <bpmn:extensionElements>
                <zeebe:subscription correlationKey="=id" />
              </bpmn:extensionElements>
            </bpmn:message>
            """.formatted(messageName);
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
            id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
          %2$s
          <bpmn:process id="%1$s" isExecutable="true">
            <bpmn:startEvent id="Start"><bpmn:outgoing>%3$s</bpmn:outgoing></bpmn:startEvent>
            %4$s
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(
        processId,
        message,
        messageName == null
            ? "ToTheEnd"
            : "ToTheWait",
        waits);

  }

  private void publishesAreAccepted() {

    final var command = mock(
        PublishMessageCommandStep1.class,
        Mockito
            .withSettings()
            .extraInterfaces(PublishMessageCommandStep2.class, PublishMessageCommandStep3.class)
            // every step of the fluent command is this one mock
            .defaultAnswer(invocation -> invocation
                .getMethod()
                .getReturnType()
                .isInstance(invocation.getMock())
                    ? invocation.getMock()
                    : Mockito.RETURNS_DEFAULTS.answer(invocation)));
    Mockito.lenient().when(((PublishMessageCommandStep3) command).send()).thenAnswer(invocation -> future(null));
    Mockito.lenient().when(client.newPublishMessageCommand()).thenReturn(command);

  }

  private void writesAreRecorded() {

    final var step2 = mock(SetVariablesCommandStep2.class);
    Mockito.lenient().when(step2.local(anyBoolean())).thenReturn(step2);
    Mockito.lenient().when(step2.send()).thenAnswer(invocation -> future(null));
    final var step1 = mock(SetVariablesCommandStep1.class);
    Mockito.lenient().when(step1.variables(Mockito.<Map<String, Object>>any())).thenReturn(step2);
    Mockito.lenient().when(client.newSetVariablesCommand(anyLong())).thenAnswer(invocation -> {
      writtenTo.add(invocation.getArgument(0));
      return step1;
    });

  }

  /**
   * @param answers What each search for called instances finds, in the order of the searches
   */
  private void calledInstancesAre(
      final List<List<Long>> answers) {

    final var remaining = new LinkedList<>(answers);
    final var search = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(client.newProcessInstanceSearchRequest()).thenReturn(search);
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> {
      final var keys = remaining.isEmpty()
          ? List.<Long>of()
          : remaining.removeFirst();
      final var instances = keys
          .stream()
          .map(key -> {
            final var instance = mock(ProcessInstance.class);
            Mockito.lenient().when(instance.getProcessInstanceKey()).thenReturn(key);
            return instance;
          })
          .toList();
      @SuppressWarnings("unchecked")
      final SearchResponse<ProcessInstance> response = mock(SearchResponse.class);
      final var page = mock(SearchResponsePage.class);
      Mockito.lenient().when(page.totalItems()).thenReturn((long) instances.size());
      Mockito.lenient().when(response.items()).thenReturn(instances);
      Mockito.lenient().when(response.page()).thenReturn(page);
      return future(response);
    });
    Mockito.lenient().when(search.filter(any(java.util.function.Consumer.class))).thenReturn(search);

  }

  /**
   * The persistence of an aggregate whose ID attribute is 'id'.
   */
  private static AggregatePersistenceAware<Object> persistence() {

    @SuppressWarnings("unchecked")
    final AggregatePersistenceAware<Object> persistence = mock(AggregatePersistenceAware.class);
    Mockito.lenient().when(persistence.getAggregateIdName()).thenReturn("id");
    return persistence;

  }

  private static <T> CamundaFuture<T> future(
      final T value) {

    @SuppressWarnings("unchecked")
    final CamundaFuture<T> future = mock(CamundaFuture.class);
    Mockito.lenient().when(future.join()).thenReturn(value);
    return future;

  }

}
