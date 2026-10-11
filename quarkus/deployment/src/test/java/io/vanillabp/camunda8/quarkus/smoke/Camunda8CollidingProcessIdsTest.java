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
 * The answer decides whether two workflow modules with the same BPMN process id end the boot.
 * Under the default mode the cluster deploys each module into a tenant named after it. So the
 * two are kept apart, and the deployment goes on. If the core cannot get an answer, it reads
 * that as "my isolation separates nothing". That would refuse an application which is
 * perfectly correct. On Quarkus the adapter hands over its deployment services as one list
 * bean per adapter id, not as single beans. So this test proves that shape. The refusal itself
 * is core logic. The Spring Boot module's {@code Camunda8CollidingProcessIdsBootTest} holds it.
 * <p>
 * No cluster is needed. The tenant a workflow module would be deployed to comes from the
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

    // what the adapter hands over while it deploys a workflow module, once per module. A
    // deployment per module cannot collect anything else
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
