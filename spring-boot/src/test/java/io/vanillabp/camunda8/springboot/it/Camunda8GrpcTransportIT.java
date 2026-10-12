package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A job really arrives over gRPC.
 * <p>
 * This adapter offers {@code prefer-rest-over-grpc: false}. Until this test, nothing here had
 * ever seen a job come in that way. The reason is the cluster which the other tests use. It
 * runs with {@code CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=true}. That unprotects REST
 * and leaves gRPC without an identity. So a gRPC activation there answers with an empty list,
 * and a gRPC command is refused with {@code PERMISSION_DENIED}. On a cluster which
 * authenticates, the transport works. A self-managed or a SaaS installation is such a cluster.
 * This test holds that the transport works there.
 * <p>
 * Two jobs, because they travel different paths through the gateway: a plain service task job
 * and the job of a user-task {@code creating} listener. The second one is why this test was
 * written at all. The REST gateway of the 8.10 alpha drops the whole activate-jobs batch when
 * it meets a listener job whose event carries no action header. That is
 * camunda/camunda#58193, and {@code creating} is one of the two events it hits. Over gRPC the
 * job arrives. It was measured on 2026-09-19 at 342 ms against
 * {@code camunda/camunda:8.10.0-alpha5}.
 * <p>
 * That is history since {@code 8.10.0-rc1}, which hands a {@code creating} job out over REST
 * as well. This class still proves the transport itself, so it stayed.
 * <p>
 * It does not recommend anything. The switch is per adapter instance, not per job type. No
 * other traffic of this repository has ever been proven on that transport. And the
 * regression it would work around is fixed upstream. The README says the same in prose.
 * <p>
 * This class has a cluster of its own. The other tests of this module share one. The shared
 * one leaves gRPC without an identity, which is the very gap described above. So this test
 * needs a cluster with a different configuration. It does not need a cluster just for itself.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
public class Camunda8GrpcTransportIT {

  @Container
  static final GenericContainer<?> CAMUNDA = ClusterUnderTest.withAuthentication();

  private static final String SERVICE_TASK_JOB_TYPE = "theServiceTaskOverGrpc";

  private static final String LISTENER_JOB_TYPE = "theListenerOverGrpc";

  private static final String MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
          id="Definitions_GrpcTransport" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="GrpcTransport" isExecutable="true">
          <bpmn:startEvent id="GrpcStart">
            <bpmn:outgoing>GrpcFlow_1</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="GrpcFlow_1" sourceRef="GrpcStart" targetRef="GrpcServiceTask" />
          <bpmn:serviceTask id="GrpcServiceTask" name="the service task">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="%s" />
            </bpmn:extensionElements>
            <bpmn:incoming>GrpcFlow_1</bpmn:incoming>
            <bpmn:outgoing>GrpcFlow_2</bpmn:outgoing>
          </bpmn:serviceTask>
          <bpmn:sequenceFlow id="GrpcFlow_2" sourceRef="GrpcServiceTask" targetRef="GrpcUserTask" />
          <bpmn:userTask id="GrpcUserTask" name="the user task">
            <bpmn:extensionElements>
              <zeebe:userTask />
              <zeebe:taskListeners>
                <zeebe:taskListener eventType="creating" type="%s" />
              </zeebe:taskListeners>
            </bpmn:extensionElements>
            <bpmn:incoming>GrpcFlow_2</bpmn:incoming>
            <bpmn:outgoing>GrpcFlow_3</bpmn:outgoing>
          </bpmn:userTask>
          <bpmn:sequenceFlow id="GrpcFlow_3" sourceRef="GrpcUserTask" targetRef="GrpcEnd" />
          <bpmn:endEvent id="GrpcEnd">
            <bpmn:incoming>GrpcFlow_3</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(SERVICE_TASK_JOB_TYPE, LISTENER_JOB_TYPE);

  @Test
  @DisplayName("A service task job and a user-task listener job both arrive over gRPC")
  public void bothKindsOfJobArriveOverGrpc() throws Exception {

    try (final var factory = grpcFactory()) {

      final var client = factory.getClient();
      assertFalse(
          client.getConfiguration().preferRestOverGrpc(),
          "the client has to speak gRPC, otherwise this test proves nothing about that transport");
      client
          .newDeployResourceCommand()
          .addResourceStringUtf8(MODEL, "grpc-transport.bpmn")
          .send()
          .join();
      client
          .newCreateInstanceCommand()
          .bpmnProcessId("GrpcTransport")
          .latestVersion()
          .send()
          .join();

      final var serviceTaskJob = awaitTheJob(client, SERVICE_TASK_JOB_TYPE);
      assertEquals(
          "GrpcServiceTask",
          serviceTaskJob.getElementId(),
          "the job which arrived is the one the model holds");
      client
          .newCompleteCommand(serviceTaskJob.getKey())
          .send()
          .join();

      // the second one is the reason for this test. The REST gateway of the 8.10 alpha drops
      // this event, and the transport is the whole difference
      final var listenerJob = awaitTheJob(client, LISTENER_JOB_TYPE);
      assertEquals("GrpcUserTask", listenerJob.getElementId());
      client
          .newCompleteCommand(listenerJob.getKey())
          .send()
          .join();

    }

  }

  /**
   * The adapter's own client factory, configured the way an installation would configure it
   * for gRPC. It is built through the adapter and not with the plain client builder. Nobody
   * had proven the adapter's gRPC configuration. The client's own was never in doubt.
   */
  private static Camunda8ClientFactory grpcFactory() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setPreferRestOverGrpc(false);
    configuration
        .setGrpcAddress("http://"
            + CAMUNDA.getHost()
            + ":"
            + CAMUNDA.getMappedPort(26500));
    // the REST address is still configured. A deployment and the searches go over REST on
    // every line. So an installation which speaks gRPC to the brokers configures both
    configuration
        .setRestAddress("http://"
            + CAMUNDA.getHost()
            + ":"
            + CAMUNDA.getMappedPort(8080));
    configuration.getAuth().setUsername(ClusterUnderTest.USERNAME);
    configuration.getAuth().setPassword(ClusterUnderTest.PASSWORD);
    return new Camunda8ClientFactory("grpc", configuration);

  }

  /**
   * The job, activated directly and not through a worker. So the test reads the answer of the
   * activation request itself.
   */
  private static ActivatedJob awaitTheJob(
      final CamundaClient client,
      final String jobType) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      final var jobs = client
          .newActivateJobsCommand()
          .jobType(jobType)
          .maxJobsToActivate(1)
          .timeout(Duration.ofMinutes(2))
          .send()
          .join()
          .getJobs();
      if (!jobs.isEmpty()) {
        final var job = jobs.getFirst();
        assertNotNull(job.getKey());
        return job;
      }
      Thread.sleep(1000);
    }
    return fail(
        "no job of type '"
            + jobType
            + "' within 120 seconds over gRPC, so this transport hands out nothing on this "
            + "cluster - which is what the 8.10 alphas did over REST, and it would now be true "
            + "of gRPC as well");

  }

}
