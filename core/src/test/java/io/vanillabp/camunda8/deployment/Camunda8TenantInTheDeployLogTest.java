package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the INFO line after a deployment names the tenant. An application which sets no tenant
 * reads {@code '<none>'}, the word the Camunda 7 adapter uses as well, and next to it the
 * tenant the cluster deploys into, whose id on Camunda 8 is {@code <default>}.
 */
public class Camunda8TenantInTheDeployLogTest {

  @Test
  @DisplayName("No tenant reads as none, and the line names the cluster's tenant next to it")
  public void noTenantReadsAsNone() {

    assertEquals("'<none>' (cluster tenant <default>)", Camunda8DeploymentService.tenantForTheLog(null));
    assertEquals("'<none>' (cluster tenant <default>)", Camunda8DeploymentService.tenantForTheLog(" "));

  }

  @Test
  @DisplayName("A tenant the application set is named as it is")
  public void aTenantIsNamedAsItIs() {

    assertEquals("'loan-approval'", Camunda8DeploymentService.tenantForTheLog("loan-approval"));

  }

}
