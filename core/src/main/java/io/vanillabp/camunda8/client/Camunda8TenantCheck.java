package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ProblemException;

/**
 * Asks a cluster whether the tenant a workflow module is about to be deployed into can
 * be used at all, before the deploy command is sent.
 *
 * <h2>Why ask at all</h2>
 *
 * A cluster started from the stock image has multi-tenancy switched off and answers a
 * deploy command carrying a tenant id with <i>"Expected to handle request Deploy
 * Resources with tenant identifier 'loan-approval', but multi-tenancy is disabled"</i>.
 * That message names the tenant, but no VanillaBP property, so the developer has to
 * find the wiki before they can act. The same holds for a tenant which simply does not
 * exist on that cluster. Both are knowable BEFORE deploying, and both come out of this
 * check as a boot failure naming the property to change.
 *
 * <h2>What is NOT reported</h2>
 *
 * Only an answer OF the cluster is interpreted, recognizable by its RFC 7807 problem
 * detail. Anything else (an unreachable cluster, a timeout, a client error) is left
 * alone: the deploy command runs into the same problem right after and reports it as
 * what it is. A diagnostic must not turn a connection problem into a wrong statement
 * about tenants.
 */
public final class Camunda8TenantCheck {

  private Camunda8TenantCheck() {
  }

  /**
   * Verifies that the given tenant exists on the cluster and that multi-tenancy is
   * enabled there.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module about to be deployed
   * @param tenantId The tenant the module would be deployed into
   * @param client The client of this adapter instance
   * @throws IllegalStateException If the cluster has multi-tenancy switched off or does
   *           not know the tenant - naming the workflow module, the tenant and the
   *           properties leading out
   */
  public static void requireUsableTenant(
      final String adapterId,
      final String workflowModuleId,
      final String tenantId,
      final CamundaClient client) {

    try {
      client
          .newTenantGetRequest(tenantId)
          .send()
          .join();
    } catch (final RuntimeException e) {
      final var problem = problemOf(e);
      if (problem == null) {
        // no answer of the cluster, so nothing is known about the tenant
        return;
      }
      if (mentionsMultiTenancy(e)) {
        throw new IllegalStateException(
            """
                Camunda 8 adapter '%s' would deploy workflow module '%s' into the tenant '%s', but \
                MULTI-TENANCY IS DISABLED on this cluster - a cluster started from the stock image \
                has it switched off, and it rejects every request carrying a tenant identifier. A \
                tenant per workflow module is what 'name-clash-avoidance: by-adapter' means, and \
                that is the DEFAULT (version 1 deployed every workflow module into a tenant named \
                after it). Either enable multi-tenancy on the cluster, or keep the workflow modules \
                apart without tenants:
                  %s: use-prefix   # VanillaBP prefixes the identifiers with the workflow module id
                  %s: none         # your identifiers are unique across all workflow modules already
                The same key may be set per workflow module \
                (vanillabp.workflow-modules.%s.adapters.%s.name-clash-avoidance)."""
                .formatted(
                    adapterId,
                    workflowModuleId,
                    tenantId,
                    modeKey(adapterId),
                    modeKey(adapterId),
                    workflowModuleId,
                    adapterId), e);
      }
      if (problem.getStatus() != null && problem.getStatus() == 404) {
        throw new IllegalStateException(
            """
                Camunda 8 adapter '%s' would deploy workflow module '%s' into the tenant '%s', but \
                this cluster does not know that tenant. Create it in the cluster (Identity, or the \
                tenant API), or point the adapter at an existing one, or keep the workflow modules \
                apart without a tenant:
                  %s: <an existing tenant>
                  %s: use-prefix   # VanillaBP prefixes the identifiers instead of using a tenant
                  %s: none         # your identifiers are unique across all workflow modules already
                Without '%s' the tenant is named after the workflow module, which is where '%s' \
                comes from - a tenant per module is what the DEFAULT mode 'by-adapter' means. The \
                name may also be set for this workflow module alone \
                ('vanillabp.workflow-modules.%s.adapters.%s.tenant-id')."""
                .formatted(
                    adapterId,
                    workflowModuleId,
                    tenantId,
                    Camunda8AdapterConfiguration.propertyKey(adapterId, "tenant-id"),
                    modeKey(adapterId),
                    modeKey(adapterId),
                    Camunda8AdapterConfiguration.propertyKey(adapterId, "tenant-id"),
                    tenantId,
                    workflowModuleId,
                    adapterId), e);
      }
      // an answer, but about something else (e.g. missing permissions for the tenant
      // API) - the deployment itself is the better place to fail
    }

  }

  private static String modeKey(
      final String adapterId) {

    return "vanillabp.adapters.%s.name-clash-avoidance".formatted(adapterId);

  }

  /**
   * The cluster's problem detail of the given failure, or <code>null</code> if the
   * failure carries none (then the cluster did not answer).
   */
  private static ProblemDetail problemOf(
      final Throwable throwable) {

    var current = throwable;
    while (current != null) {
      if (current instanceof ProblemException problem && (problem.details() != null)) {
        return problem.details();
      }
      current = current.getCause();
    }
    return null;

  }

  /**
   * Whether the cluster refused BECAUSE multi-tenancy is switched off there.
   * <p>
   * The only place in this adapter where a cluster's wording decides anything, and it
   * decides which of two guiding messages a developer reads rather than what the adapter
   * does. No code separates the two cases: the cluster answers every rejected argument
   * with the same HTTP 400, and a request naming a tenant on a cluster without
   * multi-tenancy is a rejected argument like any other. A reworded message therefore
   * costs the sharper of two sentences and nothing else - the caller has already made
   * sure an RFC 7807 answer of the cluster is what is being read.
   *
   * @param throwable What the tenant request failed with
   * @return Whether the cluster named multi-tenancy as the reason
   */
  private static boolean mentionsMultiTenancy(
      final Throwable throwable) {

    var current = throwable;
    while (current != null) {
      final var message = current.getMessage();
      if ((message != null) && message.toLowerCase().contains("multi-tenancy")) {
        return true;
      }
      current = current.getCause();
    }
    return false;

  }

}
