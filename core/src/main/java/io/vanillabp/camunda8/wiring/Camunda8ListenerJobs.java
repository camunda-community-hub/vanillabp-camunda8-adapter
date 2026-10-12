package io.vanillabp.camunda8.wiring;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8CommandRetry;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import lombok.extern.slf4j.Slf4j;

/**
 * The protocol a listener job of this adapter follows, from the moment it arrives until the
 * cluster has its answer.
 * <p>
 * A listener job is not a task. It GATES a transition the cluster is already inside: the
 * creation of a user task, the end of an element, the start of an instance. Until the job is
 * answered, the transition stands still. So a listener job is always answered. It is
 * completed where the work succeeded, and failed where it did not. This class carries the
 * protocol around that answer:
 * <ol>
 * <li>the job is registered with the {@link Camunda8Drain} of its workflow module. So a
 * shutdown waits for the handler and does not close the client under it.</li>
 * <li>both answers travel through {@link Camunda8CommandRetry}. So a rejection the cluster
 * sent for backpressure is repeated, and not turned into a lost answer.</li>
 * <li>a failure while the module is SHUTTING DOWN is not reported at all. The job is left to
 * its lock. The cluster hands it out again once the lock expires, with its retries
 * untouched.</li>
 * <li>work which is not part of the transition runs AFTER the answer went back, and still
 * inside the drain. The transition stands still while the job is open. On a
 * <code>creating</code> listener, that means the user task stands in <code>CREATING</code>.
 * There the cluster refuses every command against it. That includes the completion which
 * the application may have sent the moment it heard about the task.</li>
 * </ol>
 * <p>
 * <b>What a listener whose failure leaves no retries may expect during a shutdown.</b>
 * With no retries left, failing the job IS the incident. There is no next attempt for an
 * operator to wait for. That is the right answer to a defect and the wrong answer to a
 * restart. Nobody abandoned that work, the application was asked to stop. So a listener job
 * cut off by a shutdown is never failed here. It keeps its lock. The next instance of the
 * application gets it when the lock expires, and no incident is raised for it. A listener
 * which answers the cluster itself, without this protocol, gets an incident on every
 * rolling restart which catches a job in flight.
 * <p>
 * <b>A job which belongs to another application.</b> A worker subscribes to a job type
 * across the whole cluster. So where two applications on one cluster deploy a BPMN process
 * of the same name, each of them is served the listener jobs of the other. The core answers
 * such a delivery with {@link DeliveryOfAnUnknownWorkflowException}. This class fails the job
 * with it like any other failure, because the incident is the point. Raising the retries so
 * the delivery costs the owning application nothing would hide a fact: two applications
 * share a cluster and take each other's work. Nobody would ever find out. The incident
 * carries the message of the core, which explains the situation and does not blame whoever
 * reads it.
 * <p>
 * <b>What this protocol does not cover.</b> It covers only what happens after a handler
 * calls in. An exception thrown by the handler BEFORE that call never reaches this class.
 * The Camunda client catches it. It fails the job with one retry less than it had and
 * writes the whole stack trace into the incident. None of the rules above applies to it,
 * the shutdown rule least of all. So in front of the call, a handler does only work which
 * cannot throw. Today that is reading the job and renaming what it carries. Such a failure
 * leaves the whole stack trace in the incident. By that a red run tells it apart from a job
 * this class failed. The incident of such a job carries the one sentence
 * {@link Camunda8Errors#incidentMessage} builds.
 * <p>
 * The class is public, because an extension wires listeners into the same models and serves
 * them from the same cluster. Its listener jobs are the listener jobs of the adapter in every
 * respect a shutdown cares about. A second protocol beside this one would be a second answer
 * to the same question.
 */
@Slf4j
public final class Camunda8ListenerJobs {

  private Camunda8ListenerJobs() {
  }

  /**
   * What the listener does, and what its completion carries.
   */
  @FunctionalInterface
  public interface ListenerWork {

    /**
     * Runs the listener.
     *
     * @return The variables the completion of this job carries, empty where it carries none
     *         (never <code>null</code>). A listener completion carrying nothing is sent
     *         without a variables payload at all. The cluster refuses a TASK listener
     *         completion with one
     * @throws Exception Whatever the listener failed with
     */
    Map<String, Object> run() throws Exception;

  }

  /**
   * How a failed listener job is reported to the cluster.
   *
   * @param retriesLeft The retries the fail command leaves. Zero means the cluster raises
   *          an incident right away. A listener asks for that when its failure may not be
   *          attempted a second time
   * @param retryBackoff How long the cluster waits before handing the job out again, or
   *          <code>null</code> for no backoff. There is nothing to delay where no attempt
   *          is left
   */
  public record Failure(
                        int retriesLeft,
                        Duration retryBackoff) {

    /**
     * The answer for a listener which has no attempt left. So failing it raises the
     * incident at once.
     */
    public static final Failure NO_RETRIES_LEFT = new Failure(0, null);

  }

  /**
   * Runs a listener job and answers the cluster, following the protocol described on this
   * class.
   * <p>
   * A caller with work to do once the answer is at the cluster hands it to
   * {@link #completeOrFail(String, JobClient, ActivatedJob, Camunda8Drain, String, String, String, Supplier, ListenerWork, Runnable)}
   * instead.
   *
   * @param adapterId The adapter id whose worker delivered the job
   * @param client The job client of the worker
   * @param job The listener job
   * @param drain The drain of the workflow module this worker belongs to
   * @param kind What kind of listener this is, in the messages about a shutdown
   * @param name The task definition or the job type, as the application knows it
   * @param bpmnProcessId The BPMN process, as the application knows it
   * @param failure How a failure is reported. It is asked only when the work threw. So a
   *          caller resolving it from configuration pays nothing on the ordinary path
   * @param work The listener itself
   */
  public static void completeOrFail(
      final String adapterId,
      final JobClient client,
      final ActivatedJob job,
      final Camunda8Drain drain,
      final String kind,
      final String name,
      final String bpmnProcessId,
      final Supplier<Failure> failure,
      final ListenerWork work) {

    completeOrFail(adapterId, client, job, drain, kind, name, bpmnProcessId, failure, work, null);

  }

  /**
   * Runs a listener job, answers the cluster, and then does what the caller wants done once
   * the cluster has that answer.
   * <p>
   * The second step exists because a listener job is a transition the cluster is standing
   * in. A <code>creating</code> listener holds its user task in state <code>CREATING</code>
   * until the job is completed. The cluster refuses every command against a task in that
   * state. That includes the completion which the application may have sent the moment it
   * heard about the task. So work which is not part of the transition runs after the answer,
   * not before it. And it still runs inside the {@link Camunda8Drain} of this workflow
   * module.
   *
   * @param adapterId The adapter id whose worker delivered the job
   * @param client The job client of the worker
   * @param job The listener job
   * @param drain The drain of the workflow module this worker belongs to
   * @param kind What kind of listener this is, in the messages about a shutdown
   * @param name The task definition or the job type, as the application knows it
   * @param bpmnProcessId The BPMN process, as the application knows it
   * @param failure How a failure is reported. It is asked only when the work threw. So a
   *          caller resolving it from configuration pays nothing on the ordinary path
   * @param work The listener itself
   * @param onceTheClusterHasTheAnswer What to do after the job was completed, or
   *          <code>null</code> for nothing. It is not run for a job which was failed, left
   *          to its lock or no longer held by the cluster. And it must not throw. The job is
   *          answered by the time it runs, so a failure of it can no longer be reported to
   *          the cluster
   */
  public static void completeOrFail(
      final String adapterId,
      final JobClient client,
      final ActivatedJob job,
      final Camunda8Drain drain,
      final String kind,
      final String name,
      final String bpmnProcessId,
      final Supplier<Failure> failure,
      final ListenerWork work,
      final Runnable onceTheClusterHasTheAnswer) {

    drain.jobStarted(job.getKey(), kind, name, bpmnProcessId);
    try {
      if (!answerTheCluster(adapterId, client, job, drain, kind, name, failure, work)) {
        return;
      }
      if (onceTheClusterHasTheAnswer != null) {
        onceTheClusterHasTheAnswer.run();
      }
    } finally {
      drain.jobFinished(job.getKey());
    }

  }

  /**
   * Runs the listener and tells the cluster how it went.
   * <p>
   * A job the cluster no longer holds is not a failure here. A redelivery of the same job
   * answered it first, or the transition it gated ended another way, for example because the
   * element was canceled. Both answers to the cluster, the completion and the failure, take
   * that as a late answer and go on with a debug line.
   *
   * @return Whether the job was COMPLETED by this run. A job which was failed, a job left to
   *         its lock by a shutdown, and a job the cluster no longer held answer
   *         <code>false</code>. The follow-up of a job which is gone belongs to whoever
   *         answered it, or to nobody, because the transition it gated is over
   */
  private static boolean answerTheCluster(
      final String adapterId,
      final JobClient client,
      final ActivatedJob job,
      final Camunda8Drain drain,
      final String kind,
      final String name,
      final Supplier<Failure> failure,
      final ListenerWork work) {

    // the token of THIS activation. The cluster demands it with every answer to a leased
    // job. It is null where the worker does not lease, and then no command carries one.
    final var leaseToken = Camunda8JobLease.tokenOf(job);
    try {
      final var variables = work.run();
      return Camunda8CommandRetry
          .sendUnlessTheJobIsGone(
              adapterId,
              "completion",
              job.getKey(),
              name,
              job.getDeadline(),
              drain::isShuttingDown,
              () -> {
                var completion = Camunda8JobLease
                    .withToken(client.newCompleteCommand(job.getKey()), leaseToken);
                // a listener which carries nothing is completed without a variables payload
                // at all, not with an empty one. On a task listener, the cluster refuses the
                // payload itself.
                if (!variables.isEmpty()) {
                  completion = completion.variables(variables);
                }
                completion
                    .send()
                    .join();
              });
    } catch (final Exception e) {
      // work cut off by a shutdown is not a defect of the application. The job is left to
      // its lock, so the next instance of the application gets the listener.
      if (drain.leaveJobToItsLock(job.getKey(), kind, name, e)) {
        return false;
      }
      final var howToFail = failure.get();
      reportTheFailure(adapterId, kind, job, howToFail, e);
      Camunda8CommandRetry
          .sendUnlessTheJobIsGone(
              adapterId,
              "failure",
              job.getKey(),
              name,
              job.getDeadline(),
              drain::isShuttingDown,
              () -> {
                var command = Camunda8JobLease
                    .withToken(
                        client
                            .newFailCommand(job.getKey())
                            .retries(howToFail.retriesLeft()),
                        leaseToken);
                // no backoff where no attempt is left. There is nothing to delay.
                if (howToFail.retryBackoff() != null) {
                  command = command.retryBackoff(howToFail.retryBackoff());
                }
                command
                    .errorMessage(Camunda8Errors.incidentMessage(e))
                    .send()
                    .join();
              });
      return false;
    }

  }

  /**
   * Says in the log what the incident is about to say at the cluster.
   * <p>
   * One failure is reported differently from all the others.
   * {@link DeliveryOfAnUnknownWorkflowException} means the core found no workflow aggregate of
   * the id this job named. Where two applications share a cluster, that usually means the job
   * belongs to the other one. None of that is a defect of the code which ran. So the line
   * says what it is and leaves the stack trace out. The message names the workflow, both
   * situations it can be and what to do about each. A trace would only add which line of the
   * core read the database. Every other failure keeps its trace, because there the trace
   * shows the cause.
   *
   * @param adapterId The adapter id whose worker delivered the job
   * @param kind What kind of listener this is
   * @param job The listener job
   * @param howToFail How the failure is about to be reported to the cluster
   * @param e What the listener failed with
   */
  private static void reportTheFailure(
      final String adapterId,
      final String kind,
      final ActivatedJob job,
      final Failure howToFail,
      final Exception e) {

    final var incidentIsRaised = howToFail.retriesLeft() == 0
        ? " (an incident is raised for the operator)"
        : "";
    if (e instanceof DeliveryOfAnUnknownWorkflowException) {
      log
          .warn(
              "Camunda8[{}]: the {} job '{}' (type '{}') is about a workflow this application does "
                  + "not own - failing the job with {} retries left{}. The incident reads: {}",
              adapterId,
              kind,
              job.getKey(),
              job.getType(),
              howToFail.retriesLeft(),
              incidentIsRaised,
              e.getMessage());
      return;
    }
    log
        .warn(
            "Camunda8[{}]: processing the {} job '{}' (type '{}') failed - failing the job with {} "
                + "retries left{}",
            adapterId,
            kind,
            job.getKey(),
            job.getType(),
            howToFail.retriesLeft(),
            incidentIsRaised,
            e);

  }

}
