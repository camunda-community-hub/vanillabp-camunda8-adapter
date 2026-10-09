package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A CANARY: it watches the cluster, not this adapter.
 * <p>
 * Decision 30 says what a called process is told about the iterations of its caller, and where
 * it stops. Three properties of Camunda 8 decide where that line can run, and all three were
 * measured rather than read. A fourth decides what a push of a changed aggregate has to write
 * (decision 77). A measurement says what was true on one day, so it is asserted
 * here instead, with hand-written models and the raw client, so nothing of VanillaBP is in the
 * way.
 * <ul>
 * <li>An input mapping of a call activity reaches the CALLED instance, and whether the called
 * process is named statically or by a FEEL expression makes no difference to that. It is
 * therefore the one place a deployment can write something for a call activity whose process
 * is only known while it runs.</li>
 * <li>It reaches the called instance even with
 * <code>propagateAllParentVariables="false"</code>, where no variable of the caller arrives.
 * That is the reason decision 30 leaves such a call activity alone rather than writing a
 * mapping there: the modeller switched the caller's context off, and a mapping would hand it
 * over anyway.</li>
 * <li>What a mapping wrote travels through a SECOND call activity below it, and the mapping
 * there can extend the list it holds. Without that, anything handed down this way would reach
 * exactly one level.</li>
 * <li>A variable written into the caller AFTER the call does not reach the called instance. The
 * propagation copies the caller's variables once, when the call activity starts the called
 * instance. That is why a push of a changed aggregate writes into the called instances as well,
 * see decision 77.</li>
 * </ul>
 * <p>
 * Measured on 2026-10-01 against <code>camunda/camunda:8.8.40</code>, <code>8.9.21</code> and
 * <code>8.10.0-rc3</code>, which answered identically. The fourth was measured on 2026-10-09
 * against <code>camunda/camunda:8.10.0</code>. A red run here is news about Camunda
 * rather than a defect of this repository, and each message says what to do with the news.
 * <p>
 * What this canary deliberately does NOT hold is how big such a list may get. The cluster
 * refused one of roughly two megabytes with an incident naming <code>MAX_MESSAGE_SIZE</code>,
 * and that number belongs to a cluster's configuration rather than to anything this repository
 * promises. It is written down in the README instead.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8CallActivityVariablesCanaryIT extends TestOnTheSharedCluster {

  /**
   * The variable every model here writes with an input mapping of its call activity. Named
   * after what the adapter uses it for, so a reader of a cluster's variables finds this class.
   */
  private static final String WHAT_THE_MAPPING_WRITES = "theCanarysParents";

  /**
   * What a caller passes as an ordinary process variable, to tell the two ways a value can
   * reach a called instance apart.
   */
  private static final String WHAT_THE_CALLER_HOLDS = "theCanarysCallerVariable";

  @Test
  @DisplayName("An input mapping of a call activity still reaches the process it names by FEEL")
  public void theMappingStillReachesAProcessNamedByAnExpression() {

    try (final var client = client()) {

      deploy(client, leafCalling("TheCanarysFeelChild", "theCanarysFeelChild"));
      deploy(
          client,
          caller(
              "TheCanarysFeelCaller",
              "<zeebe:calledElement processId=\"=whichProcess\" />",
              "<zeebe:input source=\"=1\" target=\"%s\" />".formatted(WHAT_THE_MAPPING_WRITES)));

      final var job = awaitTheChildsJob(
          client,
          "TheCanarysFeelCaller",
          Map.of("whichProcess", "TheCanarysFeelChild"),
          "theCanarysFeelChild",
          List.of(WHAT_THE_MAPPING_WRITES));

      assertEquals(
          1,
          job.getVariablesAsMap().get(WHAT_THE_MAPPING_WRITES),
          "The called instance did NOT carry what the input mapping of the call activity wrote. "
              + "That is news about Camunda, not a defect of this repository: such a mapping is "
              + "the only thing a deployment can write for a call activity whose process is "
              + "named by an expression, so what the called instance can be told depends on it. "
              + "Read what the cluster does with the mapping now, and change "
              + "Camunda8MultiInstance, decision 30 and this canary together.");
      client.newCompleteCommand(job.getKey()).send().join();

    }

  }

  @Test
  @DisplayName("The mapping travels although propagateAllParentVariables switches the rest off")
  public void theMappingTravelsWhereTheCallersVariablesDoNot() {

    try (final var client = client()) {

      deploy(client, leafCalling("TheCanarysBlockedChild", "theCanarysBlockedChild"));
      deploy(client, leafCalling("TheCanarysOpenChild", "theCanarysOpenChild"));
      final var mapping = "<zeebe:input source=\"=1\" target=\"%s\" />"
          .formatted(WHAT_THE_MAPPING_WRITES);
      deploy(
          client,
          caller(
              "TheCanarysBlockedCaller",
              "<zeebe:calledElement processId=\"TheCanarysBlockedChild\" "
                  + "propagateAllParentVariables=\"false\" />",
              mapping));
      deploy(
          client,
          caller(
              "TheCanarysOpenCaller",
              "<zeebe:calledElement processId=\"TheCanarysOpenChild\" />",
              mapping));

      // the positive half first: with the propagation left alone, BOTH values arrive, so the
      // assertion below is about the attribute and not about a value which was never there
      final var withPropagation = awaitTheChildsJob(
          client,
          "TheCanarysOpenCaller",
          Map.of(WHAT_THE_CALLER_HOLDS, "the caller wrote this"),
          "theCanarysOpenChild",
          List.of());
      assertEquals(
          "the caller wrote this",
          withPropagation.getVariablesAsMap().get(WHAT_THE_CALLER_HOLDS),
          "A call activity which says nothing about propagateAllParentVariables did not hand "
              + "the caller's variable over. That is news about Camunda: the adapter reads the "
              + "iteration values of a caller out of exactly this propagation, see decision 30.");
      assertEquals(
          1,
          withPropagation.getVariablesAsMap().get(WHAT_THE_MAPPING_WRITES),
          "A call activity which says nothing about propagateAllParentVariables did not hand "
              + "its input mapping over, although it handed the caller's variables over.");
      client.newCompleteCommand(withPropagation.getKey()).send().join();

      final var withoutPropagation = awaitTheChildsJob(
          client,
          "TheCanarysBlockedCaller",
          Map.of(WHAT_THE_CALLER_HOLDS, "the caller wrote this"),
          "theCanarysBlockedChild",
          List.of());
      final var variables = withoutPropagation.getVariablesAsMap();
      assertFalse(
          variables.containsKey(WHAT_THE_CALLER_HOLDS),
          "propagateAllParentVariables=\"false\" let the caller's variable through. That is news "
              + "about Camunda: the adapter leaves such a call activity alone BECAUSE the "
              + "caller's context does not reach the called instance, see decision 30. If it "
              + "reaches it now, the chain can be reported there as well.");
      assertEquals(
          1,
          variables.get(WHAT_THE_MAPPING_WRITES),
          "propagateAllParentVariables=\"false\" also stopped the INPUT MAPPING. That is news "
              + "about Camunda: nothing may be written at such a call activity BECAUSE a "
              + "mapping travels although nothing else does, which would undermine decision 30. "
              + "If the mapping no longer travels, that reason is gone and the deployment may "
              + "write there after all.");
      client.newCompleteCommand(withoutPropagation.getKey()).send().join();

    }

  }

  @Test
  @DisplayName("A second call activity extends the list the first one wrote")
  public void theListSurvivesASecondCallActivityAndGrowsThere() {

    try (final var client = client()) {

      deploy(client, leafCalling("TheCanarysGrandchild", "theCanarysGrandchild"));
      deploy(
          client,
          caller(
              "TheCanarysSecondLevel",
              "<zeebe:calledElement processId=\"TheCanarysGrandchild\" />",
              appending("TheCanarysSecondLevel")));
      deploy(
          client,
          caller(
              "TheCanarysFirstLevel",
              "<zeebe:calledElement processId=\"TheCanarysSecondLevel\" />",
              appending("TheCanarysFirstLevel")));

      final var job = awaitTheChildsJob(
          client,
          "TheCanarysFirstLevel",
          Map.of(),
          "theCanarysGrandchild",
          List.of(WHAT_THE_MAPPING_WRITES));

      assertEquals(
          List.of(Map.of("process", "TheCanarysFirstLevel"), Map.of("process", "TheCanarysSecondLevel")),
          job.getVariablesAsMap().get(WHAT_THE_MAPPING_WRITES),
          "Two call activities did not build the list this canary expects, outermost entry "
              + "first. That is news about Camunda: a chain handed down as a variable grows by "
              + "one entry per call activity, each level appending to what it received, so a "
              + "list which does not survive the second call activity reaches one level only. "
              + "Read what the cluster hands down now, and change Camunda8MultiInstance, "
              + "decision 30 and this canary together.");
      client.newCompleteCommand(job.getKey()).send().join();

    }

  }

  @Test
  @DisplayName("A variable the caller is given after the call does not reach the called instance")
  public void aLaterVariableOfTheCallerDoesNotReachTheCalledInstance() {

    try (final var client = client()) {

      deploy(client, leafCalling("TheCanarysLateChild", "theCanarysLateChild"));
      deploy(
          client,
          caller(
              "TheCanarysLateCaller",
              "<zeebe:calledElement processId=\"TheCanarysLateChild\" />",
              ""));

      final var callerInstance = client
          .newCreateInstanceCommand()
          .bpmnProcessId("TheCanarysLateCaller")
          .latestVersion()
          .variables(Map.of(WHAT_THE_CALLER_HOLDS, "before the call"))
          .send()
          .join()
          .getProcessInstanceKey();
      // the job of the called instance exists once it can be activated, so the call happened
      final var firstLook = activateTheJob(client, "theCanarysLateChild");
      assertEquals("before the call", firstLook.getVariablesAsMap().get(WHAT_THE_CALLER_HOLDS));

      client
          .newSetVariablesCommand(callerInstance)
          .variables(Map.of(WHAT_THE_CALLER_HOLDS, "after the call"))
          .local(false)
          .send()
          .join();
      // hands the job back, so the next activation reads the variables as they are now
      client
          .newFailCommand(firstLook.getKey())
          .retries(1)
          .send()
          .join();
      final var secondLook = activateTheJob(client, "theCanarysLateChild");

      assertEquals(
          "before the call",
          secondLook.getVariablesAsMap().get(WHAT_THE_CALLER_HOLDS),
          "A variable written into the caller after the call reached the called instance. That is news about"
              + " Camunda: a push of a changed aggregate writes into every called instance itself BECAUSE the"
              + " propagation copies once, at the call (decision 77). If the cluster hands later values down now,"
              + " the push into the called instances is no longer needed.");
      client.newCompleteCommand(secondLook.getKey()).send().join();

    }

  }

  /**
   * Activates the one job of a type, waiting for it to appear.
   *
   * @param client The client of the cluster under test
   * @param jobType The job type
   * @return The activated job, with every variable its scope sees
   */
  private ActivatedJob activateTheJob(
      final CamundaClient client,
      final String jobType) {

    final var deadline = System.currentTimeMillis() + ANSWER_WITHIN.toMillis();
    while (System.currentTimeMillis() < deadline) {
      final var jobs = client
          .newActivateJobsCommand()
          .jobType(jobType)
          .maxJobsToActivate(1)
          .timeout(Duration.ofMinutes(2))
          .requestTimeout(Duration.ofSeconds(2))
          .send()
          .join()
          .getJobs();
      if (!jobs.isEmpty()) {
        return jobs.getFirst();
      }
      pauseBeforeAskingAgain();
    }
    return fail("no job of type '%s' within %s".formatted(jobType, ANSWER_WITHIN));

  }

  /**
   * The expression the adapter injects, reduced to what this canary needs: it appends one entry
   * naming the caller, and it tolerates the first call, where the variable does not exist.
   *
   * @param process The calling process, which the entry names
   * @return An input mapping, ready to be put into a call activity
   */
  private static String appending(
      final String process) {

    return ("<zeebe:input source=\"=append(if is defined(%1$s) then %1$s else [], "
        + "{ process: &#34;%2$s&#34; })\" target=\"%1$s\" />")
        .formatted(WHAT_THE_MAPPING_WRITES, process);

  }

  /**
   * Starts one workflow and hands back the job of the process at the bottom of the calls.
   *
   * @param client The client of the cluster under test
   * @param process The process to start
   * @param variables What the workflow starts with
   * @param jobType The job type of the process at the bottom
   * @param fetch Which variables the activation asks for, empty for every variable
   * @return The activated job
   */
  private ActivatedJob awaitTheChildsJob(
      final CamundaClient client,
      final String process,
      final Map<String, Object> variables,
      final String jobType,
      final List<String> fetch) {

    client
        .newCreateInstanceCommand()
        .bpmnProcessId(process)
        .latestVersion()
        .variables(variables)
        .send()
        .join();
    final var deadline = System.currentTimeMillis() + ANSWER_WITHIN.toMillis();
    while (System.currentTimeMillis() < deadline) {
      var command = client
          .newActivateJobsCommand()
          .jobType(jobType)
          .maxJobsToActivate(1)
          .timeout(Duration.ofMinutes(2));
      if (!fetch.isEmpty()) {
        command = command.fetchVariables(fetch);
      }
      final var jobs = command
          .requestTimeout(Duration.ofSeconds(2))
          .send()
          .join()
          .getJobs();
      if (!jobs.isEmpty()) {
        return jobs.getFirst();
      }
      pauseBeforeAskingAgain();
    }
    return fail(
        ("no job of type '%s' within %s, so the process '%s' never reached the bottom of its "
            + "calls and this canary has nothing to read. Look at the incidents of the cluster: "
            + "an input mapping the cluster cannot evaluate stops the call activity.")
            .formatted(jobType, ANSWER_WITHIN, process));

  }

  /**
   * How long this canary waits for the job at the bottom. Generous rather than measured: the
   * ordinary round answers within a second, and this number decides when a missing answer ends
   * the test instead of hanging.
   */
  private static final Duration ANSWER_WITHIN = Duration.ofSeconds(60);

  private static void pauseBeforeAskingAgain() {

    try {
      Thread.sleep(250);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the cluster", interrupted);
    }

  }

  private void deploy(
      final CamundaClient client,
      final String model) {

    client
        .newDeployResourceCommand()
        .addResourceStringUtf8(model, processIdOf(model)
            + ".bpmn")
        .send()
        .join();

  }

  private static String processIdOf(
      final String model) {

    final var marker = "<bpmn:process id=\"";
    final var start = model.indexOf(marker) + marker.length();
    return model.substring(start, model.indexOf('"', start));

  }

  /**
   * A process whose only activity is a call activity.
   *
   * @param processId The process
   * @param calledElement Its <code>zeebe:calledElement</code>
   * @param input Its input mapping
   * @return A BPMN file
   */
  private static String caller(
      final String processId,
      final String calledElement,
      final String input) {

    return definitions(
        processId,
        """
            <bpmn:startEvent id="Start"><bpmn:outgoing>ToTheCall</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="ToTheCall" sourceRef="Start" targetRef="TheCall" />
            <bpmn:callActivity id="TheCall">
              <bpmn:extensionElements>
                %s
                <zeebe:ioMapping>
                  %s
                </zeebe:ioMapping>
              </bpmn:extensionElements>
              <bpmn:incoming>ToTheCall</bpmn:incoming>
              <bpmn:outgoing>ToTheEnd</bpmn:outgoing>
            </bpmn:callActivity>
            <bpmn:sequenceFlow id="ToTheEnd" sourceRef="TheCall" targetRef="End" />
            <bpmn:endEvent id="End"><bpmn:incoming>ToTheEnd</bpmn:incoming></bpmn:endEvent>
            """.formatted(calledElement, input));

  }

  /**
   * A process whose only activity is a service task, so this canary can read what reached it.
   *
   * @param processId The process
   * @param jobType The job type of its service task
   * @return A BPMN file
   */
  private static String leafCalling(
      final String processId,
      final String jobType) {

    return definitions(
        processId,
        """
            <bpmn:startEvent id="Start"><bpmn:outgoing>ToTheTask</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="ToTheTask" sourceRef="Start" targetRef="TheTask" />
            <bpmn:serviceTask id="TheTask">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="%s" />
              </bpmn:extensionElements>
              <bpmn:incoming>ToTheTask</bpmn:incoming>
              <bpmn:outgoing>ToTheEnd</bpmn:outgoing>
            </bpmn:serviceTask>
            <bpmn:sequenceFlow id="ToTheEnd" sourceRef="TheTask" targetRef="End" />
            <bpmn:endEvent id="End"><bpmn:incoming>ToTheEnd</bpmn:incoming></bpmn:endEvent>
            """.formatted(jobType));

  }

  private static String definitions(
      final String processId,
      final String body) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
            id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%1$s" isExecutable="true">
        %2$s
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(processId, body);

  }

  private CamundaClient client() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

}
