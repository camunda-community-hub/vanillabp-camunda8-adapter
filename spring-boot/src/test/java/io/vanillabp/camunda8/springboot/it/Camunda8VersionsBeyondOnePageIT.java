package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.deployment.Camunda8ProcessVersions;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the adapter reads back when a cluster holds more versions of one process than a
 * search answers with.
 * <p>
 * This is the one place where the paging meets a real cluster. The unit tests of
 * {@code Camunda8SearchPages} test the adapter against a cluster which mocks play. A mock
 * cannot show that the cluster honours the cursor it handed out. Here the versions are
 * deployed and read back. So this proves the mechanism: an explicit page limit, the cursor
 * of the page before, and an answer which contains the version deployed last.
 * <p>
 * Measured on 2026-10-01 against {@code camunda/camunda:8.10.0}: 110 deployments of one
 * model took 7.1 seconds. The search reported all 110 of them 0.5 seconds later. A search
 * which named no page answered with the 100 OLDEST and left the ten newest out. Reading the
 * two pages by cursor took 66 milliseconds. That is what this class costs and what it buys.
 * <p>
 * No Spring application boots here. The process of this class is deployed with a client of
 * its own, and nothing serves it. The question is what a search answers, not what a worker
 * does with it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8VersionsBeyondOnePageIT extends TestOnTheSharedCluster {

  /**
   * The process which this class deploys. Nothing else in this module uses this name,
   * because every class here shares the cluster, and the versions below stay on it.
   */
  private static final String PROCESS = "VersionsBeyondOnePage";

  /**
   * How many versions are deployed. Ten more than the 100 which a search hands out when it is
   * not told otherwise. That is enough to tell a complete answer from a first page.
   */
  private static final int VERSIONS = 110;

  /**
   * How long the exporter may take to report the deployments. The exporter feeds the search.
   * So a busy cluster answers with fewer versions for a moment.
   */
  private static final long THE_EXPORTER_CATCHES_UP_WITHIN = 240_000L;

  private static String model(
      final int version) {

    // the documentation makes each deployment a new version. A cluster answers a resource
    // which it already holds with the version it already has
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
        id="Definitions_VersionsBeyondOnePage" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:documentation>deployment %d</bpmn:documentation>
            <bpmn:startEvent id="TheStart" />
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(PROCESS, Integer.valueOf(version));

  }

  /**
   * How many versions of this class' process the cluster says it holds. This is the total of
   * the search, not the page which came back.
   */
  private static int howManyVersionsTheClusterReports(
      final CamundaClient client) {

    return client
        .newProcessDefinitionSearchRequest()
        .filter(filter -> filter.processDefinitionId(PROCESS))
        .page(page -> page.limit(Integer.valueOf(1)))
        .send()
        .join()
        .page()
        .totalItems()
        .intValue();

  }

  @Test
  @DisplayName("Every version of a process is read back, the one deployed last included")
  public void everyVersionIsReadBackFromTheCluster() throws Exception {

    try (var client = CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build()) {

      for (var deployment = 1; deployment <= VERSIONS; deployment++) {
        client
            .newDeployResourceCommand()
            .addResourceBytes(model(deployment).getBytes(StandardCharsets.UTF_8), PROCESS
                + ".bpmn")
            .send()
            .join();
      }

      // the process ids of this module are not prefixed, and it uses no tenant. The two
      // lambdas say that: the cluster knows this process under its plain id
      final var versions = new Camunda8ProcessVersions("c8", () -> client, (
          workflowModuleId,
          bpmnProcessId) -> bpmnProcessId, workflowModuleId -> null);

      // the test waits for the TOTAL which the cluster reports, not for what the adapter
      // reads. So a run against an adapter which stops at one page fails on the answer, and
      // does not wait out this window
      final var deadline = System.currentTimeMillis() + THE_EXPORTER_CATCHES_UP_WITHIN;
      while (howManyVersionsTheClusterReports(client) < VERSIONS) {
        if (System.currentTimeMillis() > deadline) {
          throw new AssertionError(
              "the cluster reported %d of the %d deployed versions before this gave up"
                  .formatted(
                      Integer.valueOf(howManyVersionsTheClusterReports(client)),
                      Integer.valueOf(VERSIONS)));
        }
        Thread.sleep(500);
      }
      final var held = versions.versionsHeldUnder("paging-module", PROCESS);

      assertEquals(VERSIONS, held.size(), () -> "every version the cluster holds, but got "
          + held.size());
      assertTrue(
          held.contains(String.valueOf(VERSIONS)),
          () -> "the version deployed last is what the startup check compares against, and a search "
              + "reading one page answers without it: "
              + held.getLast());
      assertEquals("1", held.getFirst(), "the catalog is answered oldest first");
      assertEquals(String.valueOf(VERSIONS), held.getLast(), "and newest last");

    }

  }

}
