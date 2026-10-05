package io.vanillabp.camunda8.client;

import java.util.function.Supplier;

import io.camunda.client.CamundaClient;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether the cluster of one adapter id answers query-API requests at all - asked once
 * while the adapter deploys a workflow module, and remembered from then on.
 *
 * <h2>The answer has to be TRUE</h2>
 *
 * Finding a workflow by its aggregate's ID is a search, and so is everything the viewer
 * and the version catalog ask; a cluster started without secondary storage refuses all of
 * them, and so does a cluster whose credentials may not read what the adapter asks for.
 * This adapter serves neither: the deployment ends the boot on a remembered
 * <code>false</code>, with a message naming both reasons and both ways out - see decision
 * 20 in the repository's DECISIONS.md. So this class is asked once and read once, and
 * every other reader of the capability is gone.
 *
 * <h2>Why the answer is remembered anyway</h2>
 *
 * Because a failed search still has to be told apart from a cluster which cannot serve
 * one, and the cluster does not say which it was: it refuses a query endpoint with the
 * same HTTP 403 it uses for a request the credentials may not make, and it separates the
 * two in prose only. A failure is therefore never asked what it means. The capability is
 * settled by a request whose only purpose is to find out, and a search which fails after
 * that is an outage - which is what every message about a failed search now says.
 *
 * <h2>What is deliberately not remembered</h2>
 *
 * A cluster which cannot be REACHED while the probe runs is not declared incapable. The
 * answer stays open and the next question asks again, because an unreachable cluster says
 * nothing about what it offers once it is back - and because a boot ending on it would be
 * a boot ended by a cluster which was merely booting alongside the application.
 */
@Slf4j
public class Camunda8QueryApi {

  /**
   * What the message about a cluster refusing to be searched says about the reason,
   * because the cluster refuses for two reasons and separates them in prose only. The
   * requirement the deployment enforces is the one place which says this, and it says it
   * while both reasons are still open.
   */
  public static final String WHY_THE_CLUSTER_CANNOT_BE_SEARCHED = "either the cluster runs WITHOUT "
      + "secondary storage (camunda.data.secondary-storage.type), or this adapter's credentials "
      + "are not allowed to read what it asks for";

  /**
   * What every message about a search which failed AFTER the deployment says about the
   * reason. One of the two reasons above is not a candidate by then: the deployment
   * proved this cluster answers searches, and a cluster neither gains nor loses its
   * secondary storage while it runs. Naming the property here would send a reader to
   * check the one thing which cannot be the cause, so what is named is what can: the
   * cluster is unreachable, or a read permission was revoked underneath the application.
   */
  public static final String WHY_A_SEARCH_FAILS_AFTER_THE_DEPLOYMENT = "the cluster is not "
      + "answering, or this adapter's credentials lost their permission to read what it asks for";

  private final String adapterId;

  private final Supplier<CamundaClient> client;

  /**
   * What the cluster answered, <code>null</code> until a probe got an answer at all. A
   * cluster does not gain or lose its secondary storage while it runs, so the answer is
   * asked once. What CAN change underneath it is a read permission of the adapter's
   * credentials, revoked on the cluster's side; the adapter then meets failing searches,
   * and the messages about them name the credentials for exactly that reason.
   */
  private volatile Boolean answers;

  /**
   * Builds the query half of one adapter id. The client arrives as a supplier because the
   * factory builds this before it builds the client itself.
   *
   * @param adapterId The adapter id this asks for
   * @param client Where the client of that id comes from
   */
  public Camunda8QueryApi(
      final String adapterId,
      final Supplier<CamundaClient> client) {

    this.adapterId = adapterId;
    this.client = client;

  }

  /**
   * Whether this cluster serves query-API requests, asking it where nothing answered that
   * yet.
   * <p>
   * A search of one page holding one item is the whole question: an empty result is an
   * answer like any other, and a refusal is the only outcome which is not. The search
   * carries no filter on purpose - the cluster refuses the query endpoints as a whole, so
   * a filter would make the answer neither more reliable nor cheaper.
   *
   * @return Whether the cluster answers query-API requests
   */
  public boolean answers() {

    final var remembered = answers;
    if (remembered != null) {
      return remembered;
    }
    try {
      client
          .get()
          .newProcessInstanceSearchRequest()
          .page(page -> page.limit(1))
          .send()
          .join();
      answers = Boolean.TRUE;
    } catch (final Exception e) {
      if (!Camunda8Errors.queryApiRefused(e)) {
        log.debug(
            "Camunda8[{}]: could not find out whether the query API answers - assuming it does",
            adapterId,
            e);
        return true;
      }
      answers = Boolean.FALSE;
    }
    return answers;

  }

}
