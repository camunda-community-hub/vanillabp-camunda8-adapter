package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A BPMN process id which somebody ELSE deployed into the cluster first, against a real
 * cluster: a foreign application's file is deployed under the id this workflow module is
 * about to deploy, and the start has to say so.
 * <p>
 * Only a cluster can answer this. The adapter asks the definition search what is held under
 * the ids of the module, tells its own deployment from the answer by the resource the
 * cluster recorded, and the core words the warning out of what comes back - three steps of
 * which none can be faked without also faking the answer they are about.
 * <p>
 * The mode is <code>none</code>, which is the one this matters most in and the one a cluster
 * without multi-tenancy has to use: nothing is prefixed, no tenant separates anybody, so the
 * id the application deploys is the id the other deployment already holds. Under
 * <code>use-prefix</code> the same case needs the other application to have picked our
 * workflow module id as well, and under <code>by-adapter</code> to deploy into our tenant.
 * <p>
 * What the adapter can NOT do here is prove the holder is somebody else, because a cluster
 * records no owner - and the message says that in so many words. That sentence is part of
 * what is asserted: a reader who cannot tell a certainty from a guess learns to ignore both.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8IdentifiersTheClusterHoldsIT extends TestOnTheSharedCluster {

  /**
   * What another application deployed under the process id this test's workflow module uses:
   * a model of its own, from a file of its own, and with nothing in it this application
   * serves.
   */
  private static final String THE_PROCESS_ID_OF_ANOTHER_APPLICATION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_Foreign" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="NameClashProcess" isExecutable="true">
          <bpmn:startEvent id="F_Start">
            <bpmn:outgoing>F_ToEnd</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="F_ToEnd" sourceRef="F_Start" targetRef="F_End" />
          <bpmn:endEvent id="F_End">
            <bpmn:incoming>F_ToEnd</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @DisplayName("A process id another deployment already holds is named while booting, with both sides")
  public void aProcessIdTheClusterAlreadyHoldsIsReported(
      final CapturedOutput output) throws Exception {

    stageTheOtherApplicationsDeployment();

    final var before = output.getAll().length();
    boot().close();
    final var reported = output.getAll().substring(before);

    assertTrue(
        reported.contains("already holds"),
        () -> "the start reports what the cluster held before this deployment: "
            + reported);
    assertTrue(
        reported.contains("NameClashProcess"),
        () -> "our side is named by the identifier this application deploys: "
            + reported);
    assertTrue(
        reported.contains("another-application.bpmn"),
        () -> "their side by the resource the cluster deployed the other definition from, which is "
            + "what the adapter can say about a holder: "
            + reported);
    assertTrue(
        reported.contains("cannot tell this from an earlier deployment"),
        () -> "and the line says that this is a hint rather than a proof: "
            + reported);

  }

  /**
   * Deploys the other application's file and waits until a SEARCH answers with it.
   * <p>
   * The wait is the point: what the adapter reads is the query API, which the export pipeline
   * fills behind the deployment by an unknown amount. A boot which ran before it caught up
   * would find nothing and report nothing, which is a green test about nothing.
   */
  private static void stageTheOtherApplicationsDeployment() throws Exception {

    try (var client = testClient()) {
      client
          .newDeployResourceCommand()
          .addResourceStringUtf8(THE_PROCESS_ID_OF_ANOTHER_APPLICATION, "another-application.bpmn")
          .send()
          .join();

      final var deadline = System.currentTimeMillis() + 240_000;
      while (!theClusterAnswersWithTheOtherDefinition(client)) {
        if (System.currentTimeMillis() > deadline) {
          throw new AssertionError(
              "the query API never answered with the other application's 'NameClashProcess'");
        }
        Thread.sleep(500);
      }
    }

  }

  private static boolean theClusterAnswersWithTheOtherDefinition(
      final CamundaClient client) {

    return client
        .newProcessDefinitionSearchRequest()
        .filter(filter -> filter.processDefinitionId("NameClashProcess"))
        .send()
        .join()
        .items()
        .stream()
        .anyMatch(definition -> "another-application.bpmn".equals(definition.getResourceName()));

  }

  /**
   * A client of the test's own, for the deployment which happens while no application of
   * this class is running.
   */
  private static CamundaClient testClient() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  private static ConfigurableApplicationContext boot() {

    return new SpringApplicationBuilder(DockerTestApplication.class)
        .run(
            "--spring.config.name=camunda8-it",
            "--spring.profiles.active=name-clash",
            "--vanillabp.adapters.c8.rest-address="
                + restAddress(),
            "--vanillabp.adapters.c8.grpc-address="
                + grpcAddress(),
            // the mode the clash needs: nothing is prefixed and no tenant separates the two
            // deployments, which is what a cluster without multi-tenancy leaves an
            // application with
            "--vanillabp.adapters.c8.name-clash-avoidance=none",
            "--vanillabp.workflow-modules.test-app.adapters.c8.resources-location=classpath*:name-clash");

  }

}
