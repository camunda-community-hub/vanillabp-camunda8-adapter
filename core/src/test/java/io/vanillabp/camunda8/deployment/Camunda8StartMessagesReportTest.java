package io.vanillabp.camunda8.deployment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the boot tells the core about the messages which start a process. The core refuses a
 * message passed to <code>startWorkflowByMessage</code> which does not start the process of
 * the calling process service, and the names it compares with are the ones reported here.
 * Publishing a message in Camunda 8 names no process, so this report is the only thing which
 * keeps a message from starting a foreign process.
 * <p>
 * The cluster is an address nothing listens on. Everything asserted here is read from the
 * model.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8StartMessagesReportTest {

  private static final String MODULE = "order-module";

  private static final String PROCESS = "OrderProcess";

  private static final String FILE = "order.bpmn";

  /**
   * A process with two message start events and an event subprocess which also starts on a
   * message. The message of the event subprocess starts no workflow.
   */
  private static final String TWO_MESSAGES_AND_AN_EVENT_SUBPROCESS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="OrderProcess" isExecutable="true">
          <bpmn:startEvent id="Event_orderPlaced">
            <bpmn:messageEventDefinition id="Def_orderPlaced" messageRef="Message_orderPlaced" />
          </bpmn:startEvent>
          <bpmn:startEvent id="Event_orderImported">
            <bpmn:messageEventDefinition id="Def_orderImported" messageRef="Message_orderImported" />
          </bpmn:startEvent>
          <bpmn:subProcess id="Activity_cancel" triggeredByEvent="true">
            <bpmn:startEvent id="Event_orderCanceled" isInterrupting="true">
              <bpmn:messageEventDefinition id="Def_orderCanceled" messageRef="Message_orderCanceled" />
            </bpmn:startEvent>
          </bpmn:subProcess>
        </bpmn:process>
        <bpmn:message id="Message_orderPlaced" name="OrderPlaced" />
        <bpmn:message id="Message_orderImported" name="OrderImported" />
        <bpmn:message id="Message_orderCanceled" name="OrderCanceled">
          <bpmn:extensionElements>
            <zeebe:subscription correlationKey="=id" />
          </bpmn:extensionElements>
        </bpmn:message>
      </bpmn:definitions>
      """;

  /**
   * A process whose only start event is a plain one.
   */
  private static final String NO_MESSAGE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="OrderProcess" isExecutable="true">
          <bpmn:startEvent id="Event_start" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A process whose message start event names its message by a FEEL expression, which the
   * cluster evaluates only when the process is deployed.
   */
  private static final String A_MESSAGE_NAMED_BY_AN_EXPRESSION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="OrderProcess" isExecutable="true">
          <bpmn:startEvent id="Event_orderPlaced">
            <bpmn:messageEventDefinition id="Def_orderPlaced" messageRef="Message_orderPlaced" />
          </bpmn:startEvent>
          <bpmn:startEvent id="Event_orderImported">
            <bpmn:messageEventDefinition id="Def_orderImported" messageRef="Message_orderImported" />
          </bpmn:startEvent>
        </bpmn:process>
        <bpmn:message id="Message_orderPlaced" name="OrderPlaced" />
        <bpmn:message id="Message_orderImported" name="=&quot;Order&quot; + &quot;Imported&quot;" />
      </bpmn:definitions>
      """;

  private static BpmnModelInstance modelOf(
      final String xml) {

    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * Prepares and wires one model in the given mode, the way the core does it.
   *
   * @return The double which heard the reports
   */
  private static BpmsInitiatedStartInvoker boot(
      final NameClashAvoidance mode,
      final String xml) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(mode);
    final var bpmsInitiatedStarts = mock(BpmsInitiatedStartInvoker.class);
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker(), scoping, bpmsInitiatedStarts),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofHours(1), adapterId -> configuration, scoping);
    final var model = modelOf(xml);
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    return bpmsInitiatedStarts;

  }

  @Test
  @DisplayName("The messages of the start events the process holds are reported, without the prefix")
  public void theMessagesOfTheProcessAreReportedPlain() {

    final var bpmsInitiatedStarts = boot(NameClashAvoidance.USE_PREFIX, TWO_MESSAGES_AND_AN_EVENT_SUBPROCESS);

    // the application passes the plain name, so the core compares with the plain name. The
    // message of the event subprocess reaches a workflow which already runs and starts none
    verify(bpmsInitiatedStarts)
        .reportStartMessages("c8", MODULE, PROCESS, List.of("OrderPlaced", "OrderImported"));

  }

  @Test
  @DisplayName("A process without a message start event is reported with no message at all")
  public void aProcessWithoutAMessageStartIsReportedEmpty() {

    final var bpmsInitiatedStarts = boot(NameClashAvoidance.BY_ADAPTER, NO_MESSAGE);

    // an empty report is what makes the core refuse every message for this process
    verify(bpmsInitiatedStarts)
        .reportStartMessages("c8", MODULE, PROCESS, List.of());

  }

  @Test
  @DisplayName("A process with a message named by an expression is not reported, so the core does not check it")
  public void aMessageNamedByAnExpressionIsNotReported() {

    final var bpmsInitiatedStarts = boot(NameClashAvoidance.BY_ADAPTER, A_MESSAGE_NAMED_BY_AN_EXPRESSION);

    // reporting only 'OrderPlaced' would make the core refuse the message the expression
    // yields, although the cluster starts the process on it
    verify(bpmsInitiatedStarts, never())
        .reportStartMessages(anyString(), anyString(), anyString(), any());

  }

}
