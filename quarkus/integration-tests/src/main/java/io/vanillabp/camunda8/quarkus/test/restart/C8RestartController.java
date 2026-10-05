package io.vanillabp.camunda8.quarkus.test.restart;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * What the restart-delivery test can see of the running application: start a workflow,
 * and ask how long its first job took to arrive. A prod-mode test runs the application
 * in a forked JVM, so nothing of it can be injected into the test.
 */
@Path("/restart")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class C8RestartController {

  @Inject
  C8RestartWorkflowService workflowService;

  @POST
  @Path("/start")
  @Transactional
  public void start() {

    workflowService.startWorkflow();

  }

  @GET
  @Path("/delivery")
  public Map<String, Object> delivery() {

    final var startedAt = C8RestartWorkflowService.STARTED_AT.get();
    final var servedAt = C8RestartWorkflowService.SERVED_AT.get();
    return Map
        .of(
            "served",
            servedAt > 0,
            "millis",
            servedAt > 0
                ? servedAt - startedAt
                : -1L);

  }

}
