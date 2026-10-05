package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowElementHistory;

/**
 * The viewer/history API against a real Camunda 8 cluster:
 * <ul>
 * <li>process definitions and BPMN XML come from what THIS application version deployed,
 * carrying the cluster's real process definition key and version - a round trip the
 * adapter can spare itself even where the cluster would answer, and one it could not
 * answer consistently right after a deployment;</li>
 * <li>the element history comes from the cluster, which is what the adapter requires a
 * searchable one for.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8ViewerApiIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private ProcessService<DockerAggregate> processService;

  @Autowired
  private DockerAggregateRepository aggregateRepository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private DockerAggregate startWorkflow() {

    return transactionTemplate.execute(status -> {
      final var aggregate = new DockerAggregate();
      aggregate.setContent("viewer");
      return processService.startWorkflow(aggregateRepository.save(aggregate));
    });

  }

  @Test
  @DisplayName("Definitions and BPMN XML are served from what this application version deployed")
  public void definitionsAndXmlComeFromTheDeployment() throws Exception {

    final var aggregate = startWorkflow();

    final var definitions = processService.getProcessDefinitions(aggregate, null);

    assertEquals(1, definitions.size(), () -> "expected the deployed definition but got: "
        + definitions);
    final var definition = definitions.getFirst();
    assertTrue(
        definition
            .id()
            .startsWith("c8#"),
        () -> "process definition ids are namespaced per adapter id but got: "
            + definition.id());
    assertEquals("TestProcess", definition.bpmnProcessId());
    // the version is the one the CLUSTER assigned at deployment
    assertNotNull(definition.version());
    assertNull(definition.usedByElements());

    try (var xml = processService.getBpmnXml(definition.id())) {
      final var deployedXml = new String(xml.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(
          deployedXml.contains("TestProcess"),
          () -> "the BPMN XML has to contain the process but got: "
              + deployedXml);
      assertTrue(
          deployedXml.contains("test-job"),
          "the XML is the model AS DEPLOYED (VanillaBP's wiring included)");
    }

  }

  @Test
  @DisplayName("The element history comes from the cluster, which is what a search is required for")
  public void theElementHistoryComesFromTheCluster() throws Exception {

    final var aggregate = startWorkflow();

    // the exporter feeds the search the history is read by, so the elements arrive a
    // moment after the workflow does
    final var deadline = System.currentTimeMillis() + 240_000;
    var history = processService.getWorkflowHistory(aggregate, null);
    while ((history == null) || (history.elementsHistory() == null) || history
        .elementsHistory()
        .isEmpty()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for the element history of the started workflow");
      }
      Thread.sleep(500);
      history = processService.getWorkflowHistory(aggregate, null);
    }

    assertNotNull(history.processDefinitionId());
    final var elementIds = history
        .elementsHistory()
        .stream()
        .map(WorkflowElementHistory::elementId)
        .toList();
    assertTrue(
        elementIds.contains("start"),
        () -> "the start event the workflow came through is reported but got: "
            + elementIds);

  }

  @Test
  @DisplayName("An unknown process definition raises the SPI's guiding exception")
  public void unknownProcessDefinitionRaisesGuidingError() {

    final var exception = assertThrows(
        ProcessDefinitionNotFoundException.class,
        () -> processService.getBpmnXml("c8#123456789"));

    assertTrue(
        exception
            .getMessage()
            .contains("123456789"),
        () -> "expected a guiding message but got: "
            + exception.getMessage());

  }

}
