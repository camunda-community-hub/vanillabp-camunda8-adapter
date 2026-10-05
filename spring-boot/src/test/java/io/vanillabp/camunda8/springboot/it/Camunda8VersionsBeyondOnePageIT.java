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
 * {@code Camunda8SearchPages} hold the adapter against a cluster which is played by mocks,
 * and a mock cannot show that the cluster honours the cursor it handed out. Here the
 * versions are deployed and read back, so what is proven is the mechanism: an explicit page
 * limit, the cursor of the page before, and an answer which contains the version deployed
 * last.
 * <p>
 * Measured on 2026-10-01 against {@code camunda/camunda:8.10.0}: 110 deployments of one
 * model took 7.1 seconds, the search reported all 110 of them 0.5 seconds later, a search
 * naming no page answered with the 100 OLDEST and left the ten newest out, and reading the
 * two pages by cursor took 66 milliseconds. That is what this class costs and what it buys.
 * <p>
 * No Spring application boots here. The process of this class is deployed with a client of
 * its own and nothing serves it, because the question is what a search answers and not what
 * a worker does with it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8VersionsBeyondOnePageIT extends TestOnTheSharedCluster {

  /**
   * The process this class deploys. A name nothing else in this module uses, because every
   * class here shares the cluster and the versions below stay on it.
   */
  private static final String PROCESS = "VersionsBeyondOnePage";

  /**
   * How many versions are deployed. Ten past the 100 a search hands out without being
   * asked, which is enough to tell a complete answer from a first page.
   */
  private static final int VERSIONS = 110;

  /**
   * How long the exporter may take to report the deployments. The search is fed by it, so a
   * busy cluster answers with fewer versions for a moment.
   */
  private static final long THE_EXPORTER_CATCHES_UP_WITHIN = 240_000L;

  private static String model(
      final int version) {

    // the documentation is what makes each deployment a new version: a cluster answers a
    // resource it already holds with the version it already has
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
   * How many versions of this class' process the cluster says it holds - the total of the
   * search rather than the page which came back.
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

      // the process ids of this module are not prefixed and it uses no tenant, which is
      // what the two lambdas say: the cluster knows this process under its plain id
      final var versions = new Camunda8ProcessVersions("c8", () -> client, (
          workflowModuleId,
          bpmnProcessId) -> bpmnProcessId, workflowModuleId -> null);

      // waited for by the TOTAL the cluster reports rather than by what the adapter reads,
      // so a run against an adapter which stops at one page fails on the answer instead of
      // sitting out this window
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
