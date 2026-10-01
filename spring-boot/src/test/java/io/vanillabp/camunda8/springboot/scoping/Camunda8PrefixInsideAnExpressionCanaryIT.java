package io.vanillabp.camunda8.springboot.scoping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A CANARY: it watches the cluster, not this adapter.
 * <p>
 * Under {@code name-clash-avoidance: use-prefix} a called process or decision named by a FEEL
 * expression is deployed with the workflow module's prefix written INSIDE the expression,
 * {@code =whichProcess} becoming {@code ="loan-approval__" + string(whichProcess)}
 * ({@code Camunda8Scoping}). That frame is Camunda's FEEL and not ours, so what it does
 * belongs to the cluster: {@code +} concatenates two strings, {@code string(...)} turns the
 * application's part into one, and the parentheses hold whatever shape that part has. This
 * test holds the cluster to all of it, with the raw client so nothing of VanillaBP is in the
 * way.
 * <p>
 * A red run here is news about Camunda rather than a defect of this repository, and each
 * message says what to do with the news. The fallback, measured on the same day and working
 * on all three lines, is {@code string join(["loan-approval__", string(...)], "")}; it hides a
 * <code>null</code> of the application's part instead of raising an incident about it, which
 * is why it was not taken.
 * <p>
 * Measured on 2026-10-01 against {@code camunda/camunda:8.8.40}, {@code 8.9.21} and
 * {@code 8.10.0-rc3}: every case below answered the same on all three lines, down to the
 * wording of the incident.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8PrefixInsideAnExpressionCanaryIT extends TestOnTheSharedCluster {

  /**
   * The prefix of a workflow module called {@code loan-approval}, which is what the core
   * composes under {@code use-prefix}.
   */
  private static final String PREFIX = "loan-approval__";

  private static final String JOB_TYPE = "theJobOfThePrefixCanary";

  /**
   * The two processes the frame has to reach, deployed under prefixed ids the way a workflow
   * module of this mode is deployed.
   */
  private static final String ONE_CALLED_PROCESS = PREFIX
      + "CanaryPaymentHandling";

  private static final String THE_OTHER_CALLED_PROCESS = PREFIX
      + "CanaryOtherHandling";

  private static final String THE_CALLED_DECISION = PREFIX
      + "CanaryDecision";

  private static CamundaClient client;

  @BeforeAll
  static void deployWhatTheFrameHasToReach() {

    client = CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();
    deploy("canary-payment-handling.bpmn", aCalledProcess(ONE_CALLED_PROCESS));
    deploy("canary-other-handling.bpmn", aCalledProcess(THE_OTHER_CALLED_PROCESS));
    deploy("canary-decision.dmn", aDecision(THE_CALLED_DECISION));

  }

  @AfterAll
  static void closeTheClient() {

    if (client != null) {
      client.close();
    }

  }

  @Test
  @DisplayName("The cluster still reaches a called process through the prefix inside the expression")
  public void theFrameStillReachesACalledProcess() {

    assertEquals(
        ONE_CALLED_PROCESS,
        whichProcessWasCalled(
            "plain",
            "\"%s\" + string(whichProcess)".formatted(PREFIX),
            Map.of("whichProcess", "CanaryPaymentHandling")),
        theFrameIsBroken("a variable holding the plain process id"));

  }

  @Test
  @DisplayName("Every shape the application's part can have still survives the frame")
  public void theFrameStillHoldsEveryShape() {

    assertEquals(
        THE_OTHER_CALLED_PROCESS,
        whichProcessWasCalled(
            "if-then-else",
            "\"%s\" + string(if useTheOther then \"CanaryOtherHandling\" else \"CanaryPaymentHandling\")"
                .formatted(PREFIX),
            Map.of("useTheOther", Boolean.TRUE)),
        theFrameIsBroken("an expression returning one of several ids"));
    assertEquals(
        ONE_CALLED_PROCESS,
        whichProcessWasCalled(
            "get-value",
            "\"%s\" + string(get value(theContext, \"which\"))".formatted(PREFIX),
            Map.of("theContext", Map.of("which", "CanaryPaymentHandling"))),
        theFrameIsBroken("an expression reading a context"));
    assertEquals(
        ONE_CALLED_PROCESS,
        whichProcessWasCalled(
            "composed",
            "\"%s\" + string(\"Canary\" + theRest)".formatted(PREFIX),
            Map.of("theRest", "PaymentHandling")),
        theFrameIsBroken("an expression which composes a text itself"));
    assertEquals(
        THE_OTHER_CALLED_PROCESS,
        whichProcessWasCalled(
            "several-lines",
            ("\"%s\" + string(if useTheOther&#10;  then \"CanaryOtherHandling\"&#10;  "
                + "else \"CanaryPaymentHandling\")").formatted(PREFIX),
            Map.of("useTheOther", Boolean.TRUE)),
        theFrameIsBroken("an expression written over several lines"));

  }

  @Test
  @DisplayName("The cluster still reaches a called decision through the same frame")
  public void theFrameStillReachesACalledDecision() {

    assertEquals(
        PREFIX
            + "CallerOfTheDecision",
        whatHappened(
            aBusinessRuleTaskNaming(
                PREFIX
                    + "CallerOfTheDecision",
                "\"%s\" + string(whichDecision)".formatted(PREFIX)),
            Map.of("whichDecision", "CanaryDecision")),
        theFrameIsBroken("a business rule task, whose decision id carries the same frame"));

  }

  @Test
  @DisplayName("An application's part yielding null still raises an incident which names it")
  public void aNullOfTheApplicationStillRaisesAnIncident() {

    final var happened = whatHappened(
        aCallActivityNaming(
            PREFIX
                + "CallerOfNothing",
            "\"%s\" + string(whichProcess)".formatted(PREFIX)),
        Map.of());

    assertTrue(
        happened.startsWith("INCIDENT"),
        () -> "The cluster did NOT raise an incident for an expression yielding null. That is "
            + "news about Camunda: the frame VanillaBP writes around a FEEL-named called "
            + "process relies on the concatenation failing loudly where the application's part "
            + "is null, because the alternative frame (string join) swallows it and asks the "
            + "cluster for the bare prefix instead. Read what the cluster does now and decide "
            + "whether Camunda8Scoping still writes the better of the two. It answered: "
            + happened);
    assertTrue(
        happened.contains("whichProcess"),
        () -> "An incident, but one which does not name the variable the application's part "
            + "reads. That name is what makes the message readable for the developer whose "
            + "expression it is, and without it the frame hides their mistake. It answered: "
            + happened);

  }

  /**
   * What a red run of one of the cases above means, and what to do with it.
   */
  private static String theFrameIsBroken(
      final String whichShape) {

    return "The cluster did not reach the process this expression names, with "
        + whichShape
        + ". That is news about Camunda rather than a defect of this repository: the prefix of "
        + "a FEEL-named called process is written INSIDE the expression (see decision 2 in the "
        + "repository's DECISIONS.md and Camunda8Scoping), and that frame is Camunda's own FEEL. "
        + "Read what this line does with a string concatenation now, and change the frame, the "
        + "decision and this canary together.";

  }

  /**
   * Deploys a caller with the given call activity expression, starts it and says which process
   * its call reached, or what the cluster raised instead.
   */
  private static String whichProcessWasCalled(
      final String which,
      final String expression,
      final Map<String, Object> variables) {

    return whatHappened(
        aCallActivityNaming(PREFIX
            + "CanaryCallerOf"
            + which.replace("-", ""), expression),
        variables);

  }

  /**
   * Deploys one caller, starts it and waits for the job behind its call respectively for the
   * incident the cluster raises instead.
   *
   * @param caller The model, whose process id is read off it
   * @param variables What the instance is started with
   * @return The BPMN process id of the instance whose job arrived, or the incident
   */
  private static String whatHappened(
      final ModelOfACaller caller,
      final Map<String, Object> variables) {

    deploy(caller.processId()
        + ".bpmn", caller.xml());
    final var instance = client
        .newCreateInstanceCommand()
        .bpmnProcessId(caller.processId())
        .latestVersion()
        .variables(variables)
        .send()
        .join()
        .getProcessInstanceKey();
    final var deadline = System.currentTimeMillis() + THE_CLUSTER_ANSWERS_WITHIN.toMillis();
    while (System.currentTimeMillis() < deadline) {
      final var jobs = client
          .newActivateJobsCommand()
          .jobType(JOB_TYPE)
          .maxJobsToActivate(1)
          .timeout(Duration.ofSeconds(30))
          .requestTimeout(Duration.ofSeconds(2))
          .send()
          .join()
          .getJobs();
      if (!jobs.isEmpty()) {
        final var job = jobs.getFirst();
        client.newCompleteCommand(job.getKey()).send().join();
        return job.getBpmnProcessId();
      }
      final var incidents = client
          .newIncidentSearchRequest()
          .filter(filter -> filter.processInstanceKey(instance))
          .send()
          .join()
          .items();
      if (!incidents.isEmpty()) {
        final var incident = incidents.getFirst();
        client.newCancelInstanceCommand(instance).send().join();
        return "INCIDENT %s on '%s': %s"
            .formatted(incident.getErrorType(), incident.getElementId(), incident.getErrorMessage());
      }
      pause();
    }
    client.newCancelInstanceCommand(instance).send().join();
    return fail(
        "neither a job nor an incident within "
            + THE_CLUSTER_ANSWERS_WITHIN
            + ", so the cluster answered nothing this canary could read");

  }

  /**
   * How long one case may take. Generous: a job of a called process arrives in well under a
   * second, and an incident is read from the secondary storage, which lags by about one.
   */
  private static final Duration THE_CLUSTER_ANSWERS_WITHIN = Duration.ofSeconds(60);

  private static void pause() {

    try {
      Thread.sleep(500);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the cluster", interrupted);
    }

  }

  private static void deploy(
      final String resourceName,
      final String resource) {

    client
        .newDeployResourceCommand()
        .addResourceStringUtf8(resource, resourceName)
        .send()
        .join();

  }

  /**
   * One caller model, with the process id it declares.
   *
   * @param processId The BPMN process id of the caller
   * @param xml The model
   */
  private record ModelOfACaller(
                                String processId,
                                String xml) {
  }

  /**
   * A process with one service task, whose job says which process was reached.
   */
  private static String aCalledProcess(
      final String processId) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
            id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%1$s" isExecutable="true">
            <bpmn:startEvent id="Start"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="TheTask" />
            <bpmn:serviceTask id="TheTask">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="%2$s" />
              </bpmn:extensionElements>
              <bpmn:incoming>Flow_1</bpmn:incoming>
              <bpmn:outgoing>Flow_2</bpmn:outgoing>
            </bpmn:serviceTask>
            <bpmn:sequenceFlow id="Flow_2" sourceRef="TheTask" targetRef="End" />
            <bpmn:endEvent id="End"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(processId, JOB_TYPE);

  }

  /**
   * A caller whose call activity names its process with the given expression. The attribute is
   * single quoted, so the FEEL string literals read as they are written.
   */
  private static ModelOfACaller aCallActivityNaming(
      final String processId,
      final String expression) {

    return new ModelOfACaller(processId, """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
            id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%1$s" isExecutable="true">
            <bpmn:startEvent id="Start"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="TheCall" />
            <bpmn:callActivity id="TheCall">
              <bpmn:extensionElements>
                <zeebe:calledElement processId='=%2$s' propagateAllChildVariablesEnabled="false" />
              </bpmn:extensionElements>
              <bpmn:incoming>Flow_1</bpmn:incoming>
              <bpmn:outgoing>Flow_2</bpmn:outgoing>
            </bpmn:callActivity>
            <bpmn:sequenceFlow id="Flow_2" sourceRef="TheCall" targetRef="End" />
            <bpmn:endEvent id="End"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(processId, expression));

  }

  /**
   * A caller whose business rule task names its decision with the given expression, and a
   * service task behind it whose job says the decision was found.
   */
  private static ModelOfACaller aBusinessRuleTaskNaming(
      final String processId,
      final String expression) {

    return new ModelOfACaller(processId, """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
            id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%1$s" isExecutable="true">
            <bpmn:startEvent id="Start"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="TheRule" />
            <bpmn:businessRuleTask id="TheRule">
              <bpmn:extensionElements>
                <zeebe:calledDecision decisionId='=%3$s' resultVariable="theResult" />
              </bpmn:extensionElements>
              <bpmn:incoming>Flow_1</bpmn:incoming>
              <bpmn:outgoing>Flow_2</bpmn:outgoing>
            </bpmn:businessRuleTask>
            <bpmn:sequenceFlow id="Flow_2" sourceRef="TheRule" targetRef="TheTask" />
            <bpmn:serviceTask id="TheTask">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="%2$s" />
              </bpmn:extensionElements>
              <bpmn:incoming>Flow_2</bpmn:incoming>
              <bpmn:outgoing>Flow_3</bpmn:outgoing>
            </bpmn:serviceTask>
            <bpmn:sequenceFlow id="Flow_3" sourceRef="TheTask" targetRef="End" />
            <bpmn:endEvent id="End"><bpmn:incoming>Flow_3</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(processId, JOB_TYPE, expression));

  }

  /**
   * A decision which answers anything with one literal.
   */
  private static String aDecision(
      final String decisionId) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
            xmlns:camunda="http://camunda.org/schema/1.0/dmn"
            id="Definitions_%1$s" name="the canary" namespace="http://camunda.org/schema/1.0/dmn">
          <decision id="%1$s" name="the decision of the canary">
            <literalExpression id="Literal_1">
              <text>"the decision answered"</text>
            </literalExpression>
          </decision>
        </definitions>
        """
        .formatted(decisionId);

  }

}
