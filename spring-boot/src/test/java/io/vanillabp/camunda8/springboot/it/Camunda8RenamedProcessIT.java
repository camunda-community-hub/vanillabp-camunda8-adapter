package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Renaming a BPMN process, against a real cluster and across two generations of one
 * application: the first deploys the process under its old id and starts a workflow which
 * then waits for a message, the second deploys the same model under the NEW id and
 * declares the old one as a secondary process. The workflow started before the rename has
 * to run to its end through the methods of that second application - which is the whole
 * promise of the declaration.
 * <p>
 * Two things make this a test only a cluster can answer. The job workers of the second
 * application subscribe to the task types of the model they deployed, and the jobs they
 * are handed belong to a process id that application does not deploy any more; and the
 * message correlated for the aggregate has to reach a subscription created by the first
 * application under the old id. Both are cases where a wrong translation between the id
 * the cluster knows and the id the core is keyed by would show up, and neither can be
 * faked.
 * <p>
 * The message of that wait state carries a name only the FIRST generation's model
 * declares - the second generation renamed it. So the correlation passes phase one only
 * where the message check reads the models the cluster holds for the ids this
 * application declares, instead of the models this application version deployed; and a
 * name no model of either generation declares is still refused, with a remedy naming
 * what the cluster holds. Both are what this class measures about the check.
 * <p>
 * What is asked here is whether the workflows keep running, not what the startup check
 * reports about their versions - that report is held by
 * {@code Camunda8OldProcessVersionsIT} and by the platform's own
 * {@code RenamedBpmnProcessTest}.
 * <p>
 * The second generation's file carries a SECOND executable process which no workflow
 * service of this application claims, waiting for a message whose correlation key its
 * modeller wrote. Such a process costs the boot nothing: it is deployed with the file, the
 * core names it in the report every workflow module writes, and the workflow of the rename
 * runs through it all. What happens where its model is NOT complete for the cluster is the
 * case below: Camunda 8 answers a message catch element without a subscription by rejecting
 * the whole file, so the deployment refuses such a file rather than writing a correlation
 * key into a process this application does not serve.
 * <p>
 * The workflow module of this scenario scopes its identifiers by prefix
 * ('name-clash-avoidance: use-prefix'), which is the case a rename is hard in: a task
 * definition is deployed as '&lt;module&gt;__&lt;process&gt;__&lt;task&gt;', so the jobs of
 * the workflows under the old id are named after the OLD id and the workers of the deployed
 * processes ask for none of them. The renamed application opens a worker per task
 * definition of the declared id for exactly that reason, and this test is what says it
 * works. Under every other mode a job of the old id is named like any other and those extra
 * workers are not opened at all, which {@code Camunda8DeclaredProcessWorkersTest} holds.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Camunda8RenamedProcessIT extends TestOnTheSharedCluster {

  /**
   * The workflow started by the first application, read by the second one from the same
   * database - a workflow which outlives an upgrade is the point of this test.
   */
  static Long orderId;

  @Test
  @Order(1)
  @DisplayName("A workflow is started under the old process id and waits")
  public void aWorkflowIsStartedUnderTheOldId() throws Exception {

    final var application = boot("rename-before", "v1");
    try {
      final var workflowService = application.getBean(RenamedBeforeDockerWorkflowService.class);
      final var repository = application.getBean(RenamedDockerAggregateRepository.class);
      final var aggregate = application
          .getBean(TransactionTemplate.class)
          .execute(status -> workflowService.startWorkflow());
      orderId = aggregate.getOrderId();

      // the first task ran, so the workflow is on its way and reached the message it
      // waits for - which is where it stands while the application is upgraded
      awaitUntil(
          () -> repository.findById(orderId).orElseThrow().getStartedBy() != null,
          "the workflow of the old process id did not reach its first task");
      assertEquals(
          "before-the-rename",
          repository.findById(orderId).orElseThrow().getStartedBy(),
          "the application before the rename served the first task");
    } finally {
      application.close();
    }

  }

  @Test
  @Order(2)
  @DisplayName("The renamed application finishes the workflow which runs under the old id")
  public void theWorkflowOfTheOldIdIsFinishedAfterTheRename(
      final CapturedOutput output) throws Exception {

    assertNotNull(orderId, "the workflow of the first case has to exist");

    final var application = boot("rename-after", "v2");
    try {
      final var workflowService = application.getBean(RenamedAfterDockerWorkflowService.class);
      final var repository = application.getBean(RenamedDockerAggregateRepository.class);

      // a name NO model of either generation declares is still refused, and the
      // refusal proves the message check read the models the CLUSTER holds: the
      // remedy names 'RenameContinue', which only the old id's model declares
      final var refused = assertThrows(
          RuntimeException.class,
          () -> application
              .getBean(TransactionTemplate.class)
              .executeWithoutResult(status -> workflowService.correlateAMessageNoModelDeclares(orderId)));
      assertTrue(
          refused.getMessage().contains("DeclaredByNoModelAtAll"),
          () -> "the refusal has to name the message the application passed: "
              + refused.getMessage());
      assertTrue(
          refused.getMessage().contains("RenameContinue"),
          () -> "and what IS declared, the old id's model in the cluster included: "
              + refused.getMessage());

      // the message reaches a subscription the FIRST application created, under the id
      // this application does not deploy any more - and its name is declared only by
      // the model the cluster holds under that old id, so the check has to read it
      // there instead of refusing the correlation
      application
          .getBean(TransactionTemplate.class)
          .executeWithoutResult(status -> workflowService.continueWorkflow(orderId));

      awaitUntil(
          () -> repository.findById(orderId).orElseThrow().getFinishedBy() != null,
          "the workflow started under the old process id did not reach its last task");
      assertEquals(
          "after-the-rename",
          repository.findById(orderId).orElseThrow().getFinishedBy(),
          "the methods of the renamed application served the workflow of the old id");

      final var logged = output.getOut() + output.getErr();
      assertTrue(
          logged.contains("declared BPMN process 'RenamedProcessOld'"),
          () -> "the start has to say which workers reach the workflows of the old id: "
              + logged);
      assertTrue(
          logged.contains("test-app__RenamedProcessOld__renameFinished"),
          () -> "and name the job type the jobs of those workflows carry: "
              + logged);
      assertTrue(
          logged.contains("RenameNeighbour"),
          () -> "the process no workflow service claims has to be named while starting: "
              + logged);
      assertTrue(
          logged.contains("renamed-process-v2.bpmn"),
          () -> "together with the file it came with, which is where it is taken out: "
              + logged);
    } finally {
      application.close();
    }

  }

  /**
   * A file whose second process waits for a message without saying what to correlate it
   * by, next to a process which is complete. Sent to the cluster as it stands, which is
   * what the deployment does NOT do.
   */
  private static final String A_KEYLESS_MESSAGE_NEXT_TO_A_COMPLETE_PROCESS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_Keyless" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:message id="Msg_Keyless" name="KeylessProbe" />
        <bpmn:process id="KeylessProbeComplete" isExecutable="true">
          <bpmn:startEvent id="KP_Start">
            <bpmn:outgoing>KP_ToEnd</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="KP_ToEnd" sourceRef="KP_Start" targetRef="KP_End" />
          <bpmn:endEvent id="KP_End">
            <bpmn:incoming>KP_ToEnd</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
        <bpmn:process id="KeylessProbeWaiting" isExecutable="true">
          <bpmn:startEvent id="KW_Start">
            <bpmn:outgoing>KW_ToWait</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="KW_ToWait" sourceRef="KW_Start" targetRef="KW_Wait" />
          <bpmn:intermediateCatchEvent id="KW_Wait">
            <bpmn:incoming>KW_ToWait</bpmn:incoming>
            <bpmn:messageEventDefinition id="KW_MsgDef" messageRef="Msg_Keyless" />
          </bpmn:intermediateCatchEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @Order(3)
  @DisplayName("The cluster answers a message catch element without a subscription by rejecting the file")
  public void theClusterRejectsTheWholeFileOverAMessageWithoutASubscription() {

    // The premise of the message the deployment writes about a process nothing serves:
    // the cluster judges the FILE, so leaving such a message alone would take the process
    // next to it down as well, and refusing the file while starting is the earlier half of
    // a failure which happens either way. Measured here rather than remembered, so a
    // cluster which changes its mind about it turns this red.
    try (var client = testClient()) {
      final var rejected = assertThrows(
          RuntimeException.class,
          () -> client
              .newDeployResourceCommand()
              .addResourceStringUtf8(A_KEYLESS_MESSAGE_NEXT_TO_A_COMPLETE_PROCESS, "keyless-probe.bpmn")
              .send()
              .join(),
          "a message catch element whose message carries no zeebe:subscription is refused");
      assertTrue(
          rejected.getMessage().toLowerCase().contains("subscription"),
          () -> "and the cluster says what the model is missing: "
              + rejected.getMessage());

      assertThrows(
          RuntimeException.class,
          () -> client
              .newCreateInstanceCommand()
              .bpmnProcessId("KeylessProbeComplete")
              .latestVersion()
              .send()
              .join(),
          "the process standing next to it was not deployed either - the rejection is the "
              + "file's, not the element's");
    }

  }

  /**
   * A client of the test's own, for the one question which is asked while no application
   * of this class is running.
   */
  private static CamundaClient testClient() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  /**
   * Waits for something the cluster and the job workers have to bring about. Generous on
   * purpose: in a full build this class shares its machine with everything else the module
   * runs, and a deadline close to what a quiet machine needs fails while nothing is wrong.
   */
  private static void awaitUntil(
      final java.util.function.BooleanSupplier condition,
      final String whatDidNotHappen) throws Exception {

    final var deadline = System.currentTimeMillis() + 180_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(whatDidNotHappen);
      }
      Thread.sleep(200);
    }

  }

  /**
   * One generation of the application: the workflow service of that generation (a Spring
   * profile decides which one) and the BPMN it deploys.
   * <p>
   * Both boots share ONE in-memory database, kept alive across them by
   * {@code DB_CLOSE_DELAY=-1}: the workflow aggregate written by the first application is
   * what the second one loads when the cluster delivers the last task of that workflow.
   */
  private static ConfigurableApplicationContext boot(
      final String profile,
      final String bpmnVersion) {

    final var boot = new ArrayList<String>();
    boot.add("--spring.config.name=camunda8-it");
    boot.add("--spring.profiles.active="
        + profile);
    boot.add("--spring.datasource.url=jdbc:h2:mem:c8-renamed-process;DB_CLOSE_DELAY=-1");
    boot.add("--spring.datasource.generate-unique-name=false");
    boot.add("--spring.jpa.hibernate.ddl-auto=update");
    boot
        .add("--vanillabp.adapters.c8.rest-address="
            + restAddress());
    boot
        .add("--vanillabp.adapters.c8.grpc-address="
            + grpcAddress());
    boot.add("--vanillabp.adapters.c8.workflow-visibility-timeout=PT60S");
    // prefixed identifiers, which is what makes this test worth a cluster: a task
    // definition then carries the BPMN process id it was deployed with, so the jobs of the
    // workflows running under the OLD id are named after that id and only a worker opened
    // for the declared id reaches them
    boot.add("--vanillabp.workflow-modules.test-app.adapters.c8.name-clash-avoidance=use-prefix");
    boot
        .add("--vanillabp.workflow-modules.test-app.adapters.c8.resources-location=classpath*:renamed-process/%s"
            .formatted(bpmnVersion));
    return new SpringApplicationBuilder(DockerTestApplication.class).run(boot.toArray(String[]::new));

  }

}
