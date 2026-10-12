package io.vanillabp.camunda8.client;

/**
 * Refuses a cluster which cannot be searched, before a workflow module is deployed into
 * it.
 *
 * <h2>Why the requirement is checked at all</h2>
 *
 * Everything VanillaBP asks about a running workflow is a search. Finding a workflow by the
 * id of its aggregate is one. So is every question of the viewer and of the version
 * catalog. A cluster started without secondary storage answers none of them. Neither does
 * a cluster whose credentials may not read what the adapter asks for. The adapter used to
 * have a second behaviour per question for that state. It gave no capability, and each one
 * cost a message and a test, see decision 20 in the repository's
 * DECISIONS.md.
 *
 * <h2>Why it is asked here and not among the configuration checks</h2>
 *
 * Because it asks the CLUSTER. {@link Camunda8StartupValidation} reads properties, so it
 * can run before anything is reachable. This question needs a cluster which answers at
 * all, and the start waits for that ({@link Camunda8ClusterWait}). Asking earlier would end
 * the boot of an application whose cluster was only booting at the same time.
 * {@link Camunda8QueryApi} refuses, on purpose, to draw that conclusion.
 *
 * <h2>What is NOT reported</h2>
 *
 * An unreachable cluster. The capability comes from the remembered answer of
 * {@link Camunda8QueryApi}. That answer stays open where the probe could not reach the
 * cluster. So only a cluster which really refused a search ends a boot here.
 */
public final class Camunda8SearchableClusterCheck {

  private Camunda8SearchableClusterCheck() {
  }

  /**
   * Verifies that the cluster of this adapter instance answers searches.
   *
   * @param adapterId The adapter ID
   * @param queryApi The remembered answer of this adapter instance's cluster
   * @throws IllegalStateException If the cluster refuses to be searched. The message names
   *           the adapter, both reasons a cluster has for refusing, what to do about each of
   *           them, what the adapter cannot do without a search, and the policy which lets a
   *           non-primary adapter boot degraded anyway
   */
  public static void requireAClusterWhichCanBeSearched(
      final String adapterId,
      final Camunda8QueryApi queryApi) {

    if (queryApi.answers()) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' needs a cluster which can be SEARCHED, and this one refuses: \
            %s. Either configure secondary storage for the cluster \
            ('camunda.data.secondary-storage.type', naming the Elasticsearch or OpenSearch it \
            exports to), or give this adapter's credentials permission to read process instances, \
            process definitions, jobs, user tasks and element instances. Without a search \
            VanillaBP cannot locate a workflow by its aggregate's id, which is what elects the \
            BPMS of an operation and what a pushed workflow aggregate is written by; a version \
            specification naming a 'zeebe:versionTag' matches nothing; and the viewer serves \
            neither an element history nor a definition of an earlier application version, which \
            leaves the startup report about the versions this cluster still holds with nothing to \
            report. (An adapter which is not the first-priority adapter of a workflow module may \
            set '%s' to 'warn' and boot degraded instead - Camunda 8 as the OLD BPMS of a \
            migration off such a cluster is what that is for.)"""
            .formatted(
                adapterId,
                Camunda8QueryApi.WHY_THE_CLUSTER_CANNOT_BE_SEARCHED,
                Camunda8AdapterConfiguration.propertyKey(adapterId, "deployment-failure")));

  }

}
