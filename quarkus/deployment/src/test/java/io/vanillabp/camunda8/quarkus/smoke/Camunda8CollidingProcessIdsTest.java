package io.vanillabp.camunda8.quarkus.smoke;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.DeployedProcess;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Whether the core reaches this adapter on Quarkus when it asks whose isolation keeps two
 * workflow modules apart.
 * <p>
 * The question decides whether two workflow modules bringing one BPMN process id end the boot.
 * Under the default mode the cluster deploys each module into a tenant named after it, so the
 * two are kept apart and the deployment goes on. An answer the core cannot get reads as "my
 * isolation separates nothing", which would refuse an application that is perfectly correct,
 * and on Quarkus the adapter hands its deployment services over as one list bean per adapter
 * id rather than as element beans. So this is where that shape is proven. The refusal itself is
 * core logic and is held by the Spring Boot module's
 * {@code Camunda8CollidingProcessIdsBootTest}.
 * <p>
 * No cluster is needed: the tenant a workflow module would be deployed to comes out of
 * configuration.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CollidingProcessIdsTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(Aggregate.class)
          .addClass(SampleWorkflowService.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  private static final String ONE_MODULE = "loan-approval";

  private static final String ANOTHER_MODULE = "risk-assessment";

  private static final String SHARED_PROCESS = "Approval";

  @Inject
  NameClashAvoidanceSupport scoping;

  @Inject
  List<AdapterDeploymentService<Object, Object>> deploymentServices;

  @Test
  public void theAdapterSeparatesTwoModulesByTheirTenants() {

    Assertions
        .assertTrue(
            deploymentServices
                .getFirst()
                .ownIsolationSeparatesWorkflowModules(ONE_MODULE, ANOTHER_MODULE),
            "nothing is configured, so each module is deployed into a tenant named after it");

  }

  @Test
  public void twoModulesUnderOneProcessIdDeployIntoTheirOwnTenants() {

    // what the adapter hands over while it deploys a workflow module, once per module,
    // which is the only collection a deployment per module can have
    Assertions
        .assertDoesNotThrow(
            () -> scoping
                .validateNoCollidingProcessIds("c8", List.of(new DeployedProcess(ONE_MODULE, SHARED_PROCESS))));
    Assertions
        .assertDoesNotThrow(
            () -> scoping
                .validateNoCollidingProcessIds("c8", List.of(new DeployedProcess(ANOTHER_MODULE, SHARED_PROCESS))),
            "the core asked this adapter and was told the two tenants keep the modules apart");

  }

}
