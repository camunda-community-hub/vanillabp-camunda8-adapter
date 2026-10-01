package io.vanillabp.camunda8.springboot.scoping;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The deployment refuses a model whose job type is written as a FEEL expression, because this
 * adapter subscribes a worker to the string the model says. This case measures what the cluster
 * does with such a job type, which is the half of that reasoning Camunda owns: it starts one
 * workflow and then asks for the job under both names, the expression as it stands in the model
 * and what the expression yields.
 * <p>
 * Measured on 2026-10-01 against {@code camunda/camunda:8.10.0} and {@code camunda/camunda:8.9.21}:
 * the job carried the RESULT on both. Nothing answered under the expression itself, so a worker of
 * this adapter would have waited for a name no job ever carries. The 8.8 line runs the same case
 * in the pull request's matrix.
 * <p>
 * The refusal does not depend on this answer and says both: where the cluster evaluates the
 * expression the worker waits for a name nothing uses, and where it does not, the job carries
 * the expression and no {@code @WorkflowTask} method can be named after it. A red run here is
 * therefore news about Camunda rather than a reason to let such a model boot. What would change
 * is the sentence about what it costs, not the refusal.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8JobTypeWrittenAsAnExpressionCanaryIT extends TestOnTheSharedCluster {

  private static final String PROCESS = "CanaryJobTypeByExpression";

  /**
   * What the expression in the model says, and what it yields for the variable below.
   */
  private static final String THE_EXPRESSION = "=\"theJobOf\" + string(whichAssessment)";

  private static final String WHAT_IT_YIELDS = "theJobOfTheFullCheck";

  private static CamundaClient client;

  @BeforeAll
  static void openTheClient() {

    client = CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  @AfterAll
  static void closeTheClient() {

    if (client != null) {
      client.close();
    }

  }

  @Test
  @DisplayName("A job type written as an expression names the job by what the expression yields")
  public void theJobCarriesTheResultOfTheExpression() {

    client
        .newDeployResourceCommand()
        .addResourceStringUtf8(aProcessWhoseJobTypeIs(THE_EXPRESSION), "canary-job-type-by-expression.bpmn")
        .send()
        .join();
    final var instance = client
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS)
        .latestVersion()
        .variables(Map.of("whichAssessment", "TheFullCheck"))
        .send()
        .join()
        .getProcessInstanceKey();

    try {
      assertEquals(
          WHAT_IT_YIELDS,
          whichNameTheJobCameUnder(instance),
          "The cluster was asked for the job of a service task whose 'zeebe:taskDefinition type' is '"
              + THE_EXPRESSION
              + "', under both names: the expression as the model says it and what the expression "
              + "yields. The answer decides what a job type written as an expression costs, which "
              + "the deployment's refusal of such a model says in one sentence. Read which name "
              + "answered and correct that sentence; the refusal itself stands either way, because "
              + "neither name gives the element a worker and a @WorkflowTask method.");
    } finally {
      endWhatIsLeftOf(instance);
    }

  }

  /**
   * Asks for the job under both names until one of them answers, and completes the one which
   * does, so the workflow this canary started goes away.
   *
   * @param instance The workflow instance, ended where no name answers
   * @return The name the job came under, or what happened instead
   */
  private static String whichNameTheJobCameUnder(
      final long instance) {

    final var deadline = System.currentTimeMillis() + THE_CLUSTER_ANSWERS_WITHIN.toMillis();
    while (System.currentTimeMillis() < deadline) {
      for (final var name : new String[]{
          WHAT_IT_YIELDS, THE_EXPRESSION
      }) {
        final var jobs = client
            .newActivateJobsCommand()
            .jobType(name)
            .maxJobsToActivate(1)
            .timeout(Duration.ofSeconds(30))
            .requestTimeout(Duration.ofSeconds(2))
            .send()
            .join()
            .getJobs();
        if (!jobs.isEmpty()) {
          client.newCompleteCommand(jobs.getFirst().getKey()).send().join();
          return name;
        }
      }
      pause();
    }
    endWhatIsLeftOf(instance);
    return fail(
        "no job under either name within "
            + THE_CLUSTER_ANSWERS_WITHIN
            + ", so the cluster answered nothing this canary could read");

  }

  /**
   * Ends the workflow this canary started, where it is still running. Completing the job ends it
   * on its own, and the cluster answers a cancellation of a workflow which is gone with
   * <code>404</code> - which is the answer this canary hopes for rather than a failure of it.
   *
   * @param instance The workflow instance
   */
  private static void endWhatIsLeftOf(
      final long instance) {

    try {
      client.newCancelInstanceCommand(instance).send().join();
    } catch (final RuntimeException alreadyGone) {
      // nothing to do: the cluster holds no workflow of this canary either way, which is what
      // the cleanup is for
    }

  }

  /**
   * How long the one case may take. A job of a workflow just started arrives in well under a
   * second.
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

  /**
   * One process with one service task, whose job type is whatever is handed in.
   * <p>
   * The quotes of the FEEL string are escaped, because the value sits in an XML attribute. A
   * model written by a modeller is escaped by the modeller; one composed here is not, and
   * leaving it out refuses the deployment over the test's own XML.
   *
   * @param jobType What the task definition says
   * @return The model
   */
  private static String aProcessWhoseJobTypeIs(
      final String jobType) {

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
        .formatted(PROCESS, jobType.replace("\"", "&quot;"));

  }

}
