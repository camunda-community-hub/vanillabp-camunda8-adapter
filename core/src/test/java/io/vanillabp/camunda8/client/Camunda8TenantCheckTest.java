package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.search.response.Tenant;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Asking the cluster about the tenant before deploying (GAPS G2): which of its answers
 * becomes a guiding boot failure, and which failure is deliberately left to the
 * deployment itself.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8TenantCheckTest {

  private static final String MODULE = "loan-approval";

  /**
   * A client whose tenant lookup fails with the given exception, respectively succeeds
   * if it is <code>null</code>.
   */
  private static CamundaClient clientFailingWith(
      final RuntimeException failure) {

    final var client = Mockito.mock(CamundaClient.class, Mockito.RETURNS_DEEP_STUBS);
    final var join = Mockito
        .when(
            client
                .newTenantGetRequest(Mockito.anyString())
                .send()
                .join());
    if (failure != null) {
      join.thenThrow(failure);
    } else {
      join.thenReturn(Mockito.mock(Tenant.class));
    }
    return client;

  }

  private static ProblemException problem(
      final int status,
      final String detail) {

    return new ProblemException(
        status, detail, new ProblemDetail()
            .setStatus(status)
            .setDetail(detail));

  }

  @Test
  @DisplayName("A cluster without multi-tenancy fails the boot naming the mode property")
  public void multiTenancyDisabledIsReported() {

    final var client = clientFailingWith(
        problem(
            400,
            "Expected to handle request Deploy Resources with tenant identifier "
                + "'loan-approval', but multi-tenancy is disabled"));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> Camunda8TenantCheck.requireUsableTenant("myengine", MODULE, MODULE, client));
    final var message = exception.getMessage();
    assertTrue(message.contains("MULTI-TENANCY IS DISABLED"), () -> message);
    assertTrue(
        message.contains("vanillabp.adapters.myengine.name-clash-avoidance: use-prefix"),
        () -> message);
    // both ways out are named, not only prefixing: an application whose identifiers are
    // unique across its modules says so with 'none'
    assertTrue(
        message.contains("vanillabp.adapters.myengine.name-clash-avoidance: none"),
        () -> message);
    // and why a tenant is used at all, which matters for an application configuring nothing
    assertTrue(message.contains("by-adapter"), () -> message);
    assertTrue(message.contains("'"
        + MODULE
        + "'"), () -> message);

  }

  @Test
  @DisplayName("A tenant the cluster does not know fails the boot naming the tenant property")
  public void unknownTenantIsReported() {

    final var client = clientFailingWith(problem(404, "Tenant with id 'banking' not found"));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> Camunda8TenantCheck.requireUsableTenant("myengine", MODULE, "banking", client));
    final var message = exception.getMessage();
    assertTrue(message.contains("does not know that tenant"), () -> message);
    assertTrue(message.contains("vanillabp.adapters.myengine.tenant-id"), () -> message);
    assertTrue(message.contains("'banking'"), () -> message);
    // deploying without a tenant is the third way out and was missing here
    assertTrue(
        message.contains("vanillabp.adapters.myengine.name-clash-avoidance: none"),
        () -> message);

  }

  @Test
  @DisplayName("Anything but an answer of the cluster is left to the deployment")
  public void everythingElseStaysSilent() {

    // an existing tenant on a multi-tenancy cluster
    assertDoesNotThrow(
        () -> Camunda8TenantCheck.requireUsableTenant("myengine", MODULE, "banking", clientFailingWith(null)));

    // an unreachable cluster: no problem detail, so nothing is known about tenants -
    // the deploy command right after reports the connection failure as what it is
    assertDoesNotThrow(
        () -> Camunda8TenantCheck.requireUsableTenant(
            "myengine",
            MODULE,
            "banking",
            clientFailingWith(new IllegalStateException("Connection refused"))));

    // an answer about something else, e.g. no permission for the tenant API
    assertDoesNotThrow(
        () -> Camunda8TenantCheck.requireUsableTenant(
            "myengine",
            MODULE,
            "banking",
            clientFailingWith(problem(403, "Unauthorized to perform operation 'READ' on resource 'TENANT'"))));

  }

}
