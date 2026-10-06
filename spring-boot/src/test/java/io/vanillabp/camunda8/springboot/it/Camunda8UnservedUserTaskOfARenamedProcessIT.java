package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.util.ArrayList;
import java.util.function.Supplier;

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
import io.camunda.client.api.search.enums.ElementInstanceState;
import io.camunda.client.api.search.enums.UserTaskState;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A user task no method serves, in an old version of a renamed process, which is created
 * after the rename.
 * <p>
 * A user task may go without a {@code @WorkflowTask} method. For the models it deploys, the
 * adapter answers the {@code creating} listener of every user task, served or not, so the task
 * reaches state CREATED and shows up in a task list. Under {@code use-prefix} the listener job
 * type carries the id of the process it was deployed with. After a rename, the jobs of the
 * workflows under the OLD id are named after that id, and the renamed application opens extra
 * workers for them. Composed from the methods, those workers miss a user task nobody serves, so
 * the adapter also reads the listener job types from the models the cluster holds under the old
 * id. Without that worker nobody answers the listener and the task stays in CREATING: measured on
 * 2026-10-06 against {@code camunda/camunda:8.10.0}, the listener job stood in CREATED with no
 * worker for the whole wait below.
 * <p>
 * The first generation starts a workflow which waits for a message. The second generation
 * deploys the model under the new id, declares the old one, and sends the message. Only then
 * does the workflow reach the user task, so the task is created while the renamed application
 * runs. The first generation waits until the workflow stands at the message: its start event
 * carries a listener of that generation, and a declared id gets no start-event worker after the
 * rename.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestOnTheSharedCluster.ItsTestsAreOneScenario
public class Camunda8UnservedUserTaskOfARenamedProcessIT extends TestOnTheSharedCluster {

  /**
   * The workflow started by the first generation and continued by the second one, which
   * reads it from the same database.
   */
  static Long orderId;

  @Test
  @Order(1)
  @DisplayName("A workflow is started under the old process id and waits")
  public void aWorkflowIsStartedUnderTheOldId() throws Exception {

    final var application = boot("unserved-rename-before", "v1");
    try (var client = testClient()) {
      final var workflowService = application.getBean(UnservedRenameBeforeDockerWorkflowService.class);
      orderId = application
          .getBean(TransactionTemplate.class)
          .execute(status -> workflowService.startWorkflow())
          .getOrderId();
      // the start event carries a listener of this application, so the workflow has to get
      // past it before this generation goes down
      awaitUntil(() -> client
          .newElementInstanceSearchRequest()
          .filter(filter -> filter.elementId("UR_Wait"))
          .send()
          .join()
          .items()
          .stream()
          .anyMatch(element -> element.getState() == ElementInstanceState.ACTIVE),
          () -> "the workflow of the old process id to wait for its message; the search saw %s"
              .formatted(whereTheWorkflowsOfTheOldIdStand(client)));
    } finally {
      application.close();
    }

  }

  @Test
  @Order(2)
  @DisplayName("Its user task, created after the rename, is answered and reaches CREATED")
  public void theUserTaskOfTheOldIdIsCreated() throws Exception {

    assertNotNull(orderId, "the workflow of the first case has to exist");

    final var application = boot("unserved-rename-after", "v2");
    try (var client = testClient()) {
      final var workflowService = application.getBean(UnservedRenameAfterDockerWorkflowService.class);
      // the subscription of the message is created when the workflow reaches the wait, which
      // the first generation did not wait for, so the correlation is repeated until it lands
      awaitUntil(() -> {
        try {
          application
              .getBean(TransactionTemplate.class)
              .executeWithoutResult(status -> workflowService.continueWorkflow(orderId));
          return true;
        } catch (final RuntimeException e) {
          return false;
        }
      }, "the message to reach the workflow of the old process id");

      // first the wait for the message: once it is over, the workflow went on to the user
      // task, so what follows is about the task and not about the message. The user task
      // itself is no proof here: the search shows its element only once the creating
      // listener was answered, which is the very thing in question
      awaitUntil(() -> client
          .newElementInstanceSearchRequest()
          .filter(filter -> filter.elementId("UR_Wait"))
          .send()
          .join()
          .items()
          .stream()
          .anyMatch(element -> element.getState() == ElementInstanceState.COMPLETED),
          () -> "the workflow of the old process id to get past its message; the search saw %s"
              .formatted(whereTheWorkflowsOfTheOldIdStand(client)));

      final var seen = new StringBuilder();
      awaitUntil(() -> {
        final var tasks = client
            .newUserTaskSearchRequest()
            .filter(filter -> filter.elementId("UR_Review"))
            .send()
            .join()
            .items();
        seen.setLength(0);
        tasks.forEach(task -> seen.append(task.getUserTaskKey()).append('=').append(task.getState()).append(' '));
        return tasks.stream().anyMatch(task -> task.getState() == UserTaskState.CREATED);
      }, () -> "the user task of the old process id to reach CREATED, which needs somebody to "
          + "answer its creating listener; the search saw the user task(s) [%s] and the listener job(s) %s"
              .formatted(seen, theListenerJobsOfTheOldId(client)));
    } finally {
      application.close();
    }

  }

  /**
   * The listener jobs of the old id's user task, as the search knows them.
   *
   * @param client The client of this test
   * @return One entry per job: its key, state and the worker which had it
   */
  private static String theListenerJobsOfTheOldId(
      final CamundaClient client) {

    return client
        .newJobSearchRequest()
        .filter(filter -> filter.type("io.vanillabp.userTask:test-app__UnservedRenameOld__reviewByHand"))
        .send()
        .join()
        .items()
        .stream()
        .map(job -> "%d %s worker '%s'".formatted(job.getJobKey(), job.getState(), job.getWorker()))
        .toList()
        .toString();

  }

  /**
   * Where the workflows of the old id stand, as the search knows them: every element with
   * its state, and the variables of the workflow.
   *
   * @param client The client of this test
   * @return One entry per workflow
   */
  private static String whereTheWorkflowsOfTheOldIdStand(
      final CamundaClient client) {

    return client
        .newProcessInstanceSearchRequest()
        .send()
        .join()
        .items()
        .stream()
        .filter(instance -> instance.getProcessDefinitionId().contains("UnservedRename"))
        .map(instance -> "%s %d %s: elements %s, variables %s".formatted(
            instance.getProcessDefinitionId(),
            instance.getProcessInstanceKey(),
            instance.getState(),
            client
                .newElementInstanceSearchRequest()
                .filter(filter -> filter.processInstanceKey(instance.getProcessInstanceKey()))
                .send()
                .join()
                .items()
                .stream()
                .map(element -> "%s=%s".formatted(element.getElementId(), element.getState()))
                .toList(),
            client
                .newVariableSearchRequest()
                .filter(filter -> filter.processInstanceKey(instance.getProcessInstanceKey()))
                .send()
                .join()
                .items()
                .stream()
                .map(variable -> "%s=%s".formatted(variable.getName(), variable.getValue()))
                .toList()))
        .toList()
        .toString();

  }

  private static CamundaClient testClient() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  private static void awaitUntil(
      final java.util.function.BooleanSupplier condition,
      final String whatDidNotHappen) throws Exception {

    awaitUntil(condition, () -> whatDidNotHappen);

  }

  /**
   * Waits for something the cluster and the job workers have to bring about. A minute is
   * long for a listener job: the first generation's listener answers one within a second.
   */
  private static void awaitUntil(
      final java.util.function.BooleanSupplier condition,
      final Supplier<String> whatDidNotHappen) throws Exception {

    final var deadline = System.currentTimeMillis() + 60_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(whatDidNotHappen.get());
      }
      Thread.sleep(500);
    }

  }

  /**
   * One generation of the application, like the one of {@code Camunda8RenamedProcessIT}:
   * the workflow service of that generation (a Spring profile decides which one) and the
   * BPMN it deploys, over one in-memory database both boots share.
   */
  private static ConfigurableApplicationContext boot(
      final String profile,
      final String bpmnVersion) {

    final var boot = new ArrayList<String>();
    boot.add("--spring.config.name=camunda8-it");
    boot.add("--spring.profiles.active=%s".formatted(profile));
    boot.add("--spring.datasource.url=jdbc:h2:mem:c8-unserved-rename;DB_CLOSE_DELAY=-1");
    boot.add("--spring.datasource.generate-unique-name=false");
    boot.add("--spring.jpa.hibernate.ddl-auto=update");
    boot.add("--vanillabp.adapters.c8.rest-address=%s".formatted(restAddress()));
    boot.add("--vanillabp.adapters.c8.grpc-address=%s".formatted(grpcAddress()));
    // prefixed identifiers: the listener job type then carries the process id the model was
    // deployed with, which is what makes the old id need workers of its own
    boot.add("--vanillabp.workflow-modules.test-app.adapters.c8.name-clash-avoidance=use-prefix");
    boot
        .add("--vanillabp.workflow-modules.test-app.adapters.c8.resources-location=classpath*:unserved-rename/%s"
            .formatted(bpmnVersion));
    boot.add("--vanillabp.workflow-modules.test-app.workflows.UnservedRenameOld.allow-full-sync-with-bpms=true");
    boot.add("--vanillabp.workflow-modules.test-app.workflows.UnservedRenameNew.allow-full-sync-with-bpms=true");
    return new SpringApplicationBuilder(DockerTestApplication.class).run(boot.toArray(String[]::new));

  }

}
