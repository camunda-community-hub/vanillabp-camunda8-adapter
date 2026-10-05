package io.vanillabp.camunda8.client;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ProblemException;
import io.grpc.Status;

/**
 * Shared classification of Camunda 8 client errors. Two kinds of caller ask.
 * <p>
 * A caller which SENT something asks whether another attempt can change the answer:
 * whether a job-based command failed because the job is GONE (already completed, canceled
 * by a boundary event, or the workflow moved on) - the at-least-once residual tolerated by
 * completions and mapped to UNKNOWN_TO_BPMS by awareness probes - and whether a phase-two
 * operation is refused the same way every time. Everything else is treated as an
 * infrastructure failure.
 * <p>
 * A caller which READ something asks what the cluster's answer means about the state it
 * asked for: {@link #notFound(Throwable)} is the reading side of the same code, where it
 * is usually the exporter which has not caught up rather than a failure. That side is what
 * this class is public for.
 * <p>
 * Every question is answered from the codes of the two transports, down the failure's
 * cause chain, and never from the words the cluster wraps around them. Why a job which is
 * gone is final even though the classification is otherwise generous is decision 9 in the
 * repository's DECISIONS.md.
 */
public final class Camunda8Errors {

  private Camunda8Errors() {
  }

  /**
   * The first failure in the chain of causes which answers the question, or
   * <code>null</code> where none does.
   * <p>
   * Every classification here reads a cause chain, and a cause chain is not guaranteed to
   * end. A client which wraps a failure it already wrapped hands over a ring, and the walk
   * would then run forever inside a job handler or an outbox dispatch, which is worse than
   * any answer it could have given. Remembering the failures already seen, by identity
   * rather than by <code>equals</code>, ends the walk on the second sight of one - a self
   * reference and a longer ring alike.
   */
  private static Throwable firstCauseAnswering(
      final Throwable throwable,
      final Predicate<Throwable> question) {

    final var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    var current = throwable;
    while ((current != null) && seen.add(current)) {
      if (question.test(current)) {
        return current;
      }
      current = current.getCause();
    }
    return null;

  }

  /**
   * Whether any failure in the chain of causes answers the question, bounded as
   * {@link #firstCauseAnswering(Throwable, Predicate)} describes.
   */
  private static boolean anyCauseAnswers(
      final Throwable throwable,
      final Predicate<Throwable> question) {

    return firstCauseAnswering(throwable, question) != null;

  }

  /**
   * Whether the cluster answered "I do not hold that" - the one answer which means the
   * addressed key is not there, whatever was addressed by it.
   * <p>
   * Both transports say it with a code of their own: the REST gateway answers with HTTP
   * <code>404</code> (a {@link ProblemException} carries it as well, it extends
   * {@link ClientHttpException}), the gRPC gateway with the status
   * <code>NOT_FOUND</code>. Nothing here reads the message text - the words the cluster
   * wraps around that code are the cluster's to change.
   * <p>
   * Public because the answer means different things to different callers and every one
   * of them has to recognise it first: for a job command it is a job which is gone (see
   * {@link #jobAlreadyGone(Throwable)}), for a read of something just written it is the
   * exporter which has not caught up yet, and reading either from ONE of the two codes
   * turns the other transport's answer into a hard failure.
   *
   * @param throwable What the command or the request failed with
   * @return Whether the cluster does not (or does not yet) hold what was addressed
   */
  public static boolean notFound(
      final Throwable throwable) {

    return anyCauseAnswers(
        throwable,
        cause -> ((cause instanceof ClientHttpException http) && (http
            .code() == 404)) || ((cause instanceof ClientStatusException status) && (status
                .getStatusCode() == Status.Code.NOT_FOUND)));

  }

  /**
   * Whether the given failure means "this job does not exist (anymore)".
   * <p>
   * A job command addresses one key and nothing else, so the cluster not holding that key
   * is the whole answer: {@link #notFound(Throwable)} is what it reads, and what it adds
   * is the meaning, which is that the job was completed, canceled or otherwise moved on.
   *
   * @param throwable The failure of a job-based command
   * @return Whether the job is gone
   */
  public static boolean jobAlreadyGone(
      final Throwable throwable) {

    return notFound(throwable);

  }

  /**
   * Whether the cluster refused a job command because the job is THERE but not activated
   * right now - the answer which says the BPMS still holds the task.
   * <p>
   * Measured on 8.8.37, 8.9.19 and 8.10.0-alpha5 with the same answer on all three: an
   * <code>UpdateJobTimeout</code> against a job which is not locked is refused with HTTP
   * <code>400</code>, on gRPC with <code>INVALID_ARGUMENT</code>. Three situations produce
   * it and the BPMS holds the task in all three - the job waits in the queue and was never
   * activated, its lock ran out and it went back into the queue, or it failed with no retry
   * left and an incident is open on it.
   * <p>
   * The middle one happens in normal operation. An asynchronous task keeps its job locked
   * for <code>async-task-lock-renewal</code>, and when that hour passes the cluster puts the
   * job back into the queue until a worker takes it again. Reading that gap as an outage
   * sends the caller into retries for a task which is perfectly alive.
   * <p>
   * The CODE alone carries the meaning here, so decision 16 in the repository's DECISIONS.md
   * needs no widening: a job command names one key, the cluster answers a key it does not
   * hold with {@link #notFound(Throwable)}, and a refusal which is not that one is a refusal
   * about a job the cluster HAS. Which of the three situations it is does not change the
   * answer, so the sentence the cluster writes around the code is not read.
   *
   * @param throwable The failure of a job-based command
   * @return Whether the cluster holds the job and refused the command anyway
   */
  public static boolean jobIsThereButNotActive(
      final Throwable throwable) {

    return !notFound(throwable) && anyCauseAnswers(
        throwable,
        cause -> ((cause instanceof ClientHttpException http) && (http
            .code() == 400)) || ((cause instanceof ClientStatusException status) && (status
                .getStatusCode() == Status.Code.INVALID_ARGUMENT)));

  }

  /**
   * Whether the cluster refused a command of a job because ANOTHER activation holds that
   * job - what an answer of a run whose lock had expired meets.
   * <p>
   * A worker of this adapter may lease its activations (see decision 36 in the
   * repository's DECISIONS.md). The cluster then takes the completion, the failure and the
   * BPMN error of a job only from whoever carries the token of the CURRENT activation, and
   * an activation which followed an expired lock supersedes the token before it. So the
   * run which finished first is refused and the values of the run which finished last are
   * what the workflow continues with.
   * <p>
   * Measured on 8.10.0-alpha5: a completion carrying a superseded token is refused with
   * HTTP <code>409</code>, title <code>INVALID_STATE</code>, on gRPC with
   * <code>FAILED_PRECONDITION</code>, and a completion carrying no token at all against a
   * leased job with the same pair. A failure and a BPMN error answer the same. Every other
   * wrong state of a job command measured there is a <code>404</code>: a key which never
   * existed, a job which is already completed, and a token handed to a job which carries no
   * lease.
   * <p>
   * The code alone cannot say WHICH wrong state the cluster means - the REST specification
   * says 409 is "the job is in the wrong state" and nothing narrower, and the sentence
   * around it is the cluster's to reword (decision 16 in the repository's DECISIONS.md). So
   * the rule this adapter follows is the honest one: a 409 of a JOB command means somebody
   * else holds this activation, whatever the reason. Both readings end the same way - the
   * command is not repeated, because no repetition of it is going to be accepted either.
   *
   * @param throwable The failure of a job-based command
   * @return Whether another activation of that job is the one the cluster is holding
   */
  public static boolean jobHeldByAnotherActivation(
      final Throwable throwable) {

    return !notFound(throwable) && anyCauseAnswers(
        throwable,
        cause -> ((cause instanceof ClientHttpException http) && (http
            .code() == 409)) || ((cause instanceof ClientStatusException status) && (status
                .getStatusCode() == Status.Code.FAILED_PRECONDITION)));

  }

  /**
   * Whether the cluster refused a publication because a message of the same id was
   * published before and still lives - the answer which makes an outbox entry done
   * rather than repeated, because a repetition would be refused again.
   * <p>
   * Both transports name the rejection with a code, and this adapter uses both: a
   * publication travels REST or gRPC depending on
   * <code>vanillabp.adapters.&lt;id&gt;.prefer-rest-over-grpc</code>. On gRPC the
   * rejection arrives as the status <code>ALREADY_EXISTS</code>, on REST as HTTP
   * <code>409</code> (whose problem detail carries the same word as its title). No other
   * conflict reaches a publication, so the code alone settles it, and the sentence the
   * cluster writes around it stays the cluster's to reword.
   *
   * @param throwable What the publish command threw
   * @return Whether the message was published before
   */
  public static boolean messageAlreadyPublished(
      final Throwable throwable) {

    return anyCauseAnswers(
        throwable,
        cause -> ((cause instanceof ClientHttpException http) && (http
            .code() == 409)) || ((cause instanceof ClientStatusException status) && (status
                .getStatusCode() == Status.Code.ALREADY_EXISTS)));

  }

  /**
   * Whether the cluster refused a command ABOUT AN INSTANCE IT HOLDS - the answer which
   * makes a deliberately refused command a question about whether the engine has that
   * instance at all.
   * <p>
   * An engine addressed by an instance key answers a key it does not hold with
   * {@link #notFound(Throwable)}, and it forgets an instance the moment it ends. Everything
   * it refuses for a reason of its own is therefore about an instance it HAS: a
   * modification naming an element the model does not have is rejected with HTTP
   * <code>400</code> (on gRPC <code>INVALID_ARGUMENT</code>), and a business id assigned to
   * an instance which already carries one with HTTP <code>409</code> (on gRPC
   * <code>FAILED_PRECONDITION</code>).
   * <p>
   * The list is spelled out rather than written as "anything which is not a 404", because
   * an expired token and a missing permission are refusals too and they say nothing about
   * the instance. Which command is sent and why both of them are refused rather than
   * carried out is decision 35 in the repository's DECISIONS.md.
   *
   * @param throwable What the command about one instance threw
   * @return Whether the cluster refused it about an instance it holds
   */
  public static boolean refusedAboutAnInstanceItHolds(
      final Throwable throwable) {

    return !notFound(throwable) && anyCauseAnswers(
        throwable,
        cause -> ((cause instanceof ClientHttpException http) && ((http.code() == 400) || (http
            .code() == 409))) || ((cause instanceof ClientStatusException status) && ((status
                .getStatusCode() == Status.Code.INVALID_ARGUMENT) || (status
                    .getStatusCode() == Status.Code.FAILED_PRECONDITION))));

  }

  /**
   * Whether the cluster refused a command ABOUT A USER TASK IT HOLDS - the answer which
   * makes the empty update of {@link Camunda8UserTaskProbe} a question about whether the
   * task is still open, and which keeps a completion from being read as a task which is
   * gone.
   * <p>
   * A user task the cluster no longer has is answered with {@link #notFound(Throwable)}.
   * HTTP <code>409</code> (on gRPC <code>FAILED_PRECONDITION</code>) is something else. It
   * was measured for three states of a task the cluster was holding: a task standing in
   * <code>UPDATING</code>, a task whose modelled <code>updating</code> listener denied the
   * update, and a task standing in <code>CREATING</code> because a <code>creating</code>
   * listener of it had not been answered yet. The third one is the everyday case, because
   * VanillaBP notifies the application FROM such a listener: an application which completes
   * its user task right away addresses a task the cluster is still creating, and both the
   * empty update and the completion answer <code>409</code> while that lasts.
   * <p>
   * HTTP <code>400</code> is deliberately NOT in here, although the endpoint documents it.
   * No run has ever produced one for a user task, so what it would mean is a guess, and a
   * guess in this direction turns an open task into a canceled one.
   *
   * @param throwable What the command about one user task threw
   * @return Whether the cluster refused it about a user task it holds
   */
  public static boolean refusedAboutAUserTaskItHolds(
      final Throwable throwable) {

    return !notFound(throwable) && anyCauseAnswers(
        throwable,
        cause -> ((cause instanceof ClientHttpException http) && (http
            .code() == 409)) || ((cause instanceof ClientStatusException status) && (status
                .getStatusCode() == Status.Code.FAILED_PRECONDITION)));

  }

  /**
   * Whether the cluster REFUSED a query-API request, which is what a cluster does that
   * cannot be searched at all.
   * <p>
   * The searches of this adapter travel REST only - the client offers no gRPC equivalent
   * for them - and the cluster refuses them with HTTP <code>403</code>. That code does
   * not say WHY, see {@link Camunda8QueryApi}, which is why only the probe asks this
   * question and everything else reads the remembered answer.
   *
   * @param throwable What a query-API request failed with
   * @return Whether the cluster refused to answer it
   */
  public static boolean queryApiRefused(
      final Throwable throwable) {

    return anyCauseAnswers(
        throwable,
        cause -> (cause instanceof ClientHttpException http) && (http.code() == 403));

  }

  /**
   * Whether the cluster refused to START a workflow in a way every further attempt meets
   * again. Measured against a cluster of the tested line on 2026-09-11, over both
   * transports where both could be asked:
   * <ul>
   * <li>the process is not deployed on this cluster - HTTP <code>404</code> with the
   * title <code>NOT_FOUND</code>, on gRPC the status <code>NOT_FOUND</code>;</li>
   * <li>the model has no plain start event, so nothing can be started without the
   * message or the timer the model asks for - HTTP <code>409</code> with the title
   * <code>INVALID_STATE</code>.</li>
   * </ul>
   * Neither of them passes while the application waits: a deployment reaches the cluster
   * before the outbox dispatches anything, and a model is changed by a deployment and
   * never by a repetition. So the start behind such an answer is better parked where an
   * operator finds it than repeated for hours, which is what a start whose aggregate is
   * committed would otherwise be.
   * <p>
   * The gRPC equivalent of the second one is not in here because it could not be
   * measured: the cluster the tests run against answers a gRPC create of a deployed
   * process by refusing the permission, which is permanent for other reasons. What is
   * classified is what was measured, so an installation on gRPC meeting a model without
   * a plain start event still pays the full row of attempts.
   *
   * @param throwable What the create command threw
   * @return Whether this start is refused the same way however often it is sent
   */
  public static boolean startRefusedForGood(
      final Throwable throwable) {

    return notFound(throwable) || anyCauseAnswers(
        throwable,
        cause -> (cause instanceof ClientHttpException http) && (http.code() == 409));

  }

  /**
   * HTTP statuses of the REST transport a repetition cannot change:
   * <ul>
   * <li><code>400</code> - the cluster rejected the request itself,</li>
   * <li><code>403</code> - the credentials or the tenant are wrong, not late,</li>
   * <li><code>405</code> and <code>501</code> - this cluster version has no such
   * endpoint.</li>
   * </ul>
   * Deliberately NOT in the list: <code>404</code> is the signature of eventual
   * consistency (and for job commands it never gets here, see
   * {@link #jobAlreadyGone(Throwable)}), <code>401</code> is usually an expired token
   * the client refreshes, and <code>409</code>, <code>429</code> and every
   * <code>5xx</code> are exactly what the outbox repeats for. Two of them mean something
   * else when a START meets them, which is why a start brings its own answer along, see
   * {@link Camunda8RefusedStart}.
   */
  private static final Set<Integer> PERMANENT_HTTP_STATUS = Set.of(400, 403, 405, 501);

  /**
   * The gRPC equivalents of {@link #PERMANENT_HTTP_STATUS}, for the commands still
   * travelling that transport. <code>NOT_FOUND</code>, <code>UNAUTHENTICATED</code>,
   * <code>ABORTED</code>, <code>RESOURCE_EXHAUSTED</code> and
   * <code>UNAVAILABLE</code> stay repeatable for the reasons given there.
   */
  private static final Set<Status.Code> PERMANENT_GRPC_CODES = Set
      .of(
          Status.Code.INVALID_ARGUMENT,
          Status.Code.PERMISSION_DENIED,
          Status.Code.UNIMPLEMENTED);

  /**
   * Whether repeating a phase-two operation which failed like this cannot help,
   * so the outbox entry is blocked at once instead of being retried until
   * its attempts are used up.
   * <p>
   * The list is short on purpose: repeating is the safe answer and stays the default
   * for everything not named here. {@link ProblemException} needs no rule of its own,
   * it extends {@link ClientHttpException}.
   * <p>
   * One operation adds an answer of its own, because the same code means different
   * things depending on what was sent: a start which the cluster refused for good says
   * so by wrapping the refusal into a {@link Camunda8RefusedStart}, and nothing about
   * those codes changes for the operations which did not send a start.
   *
   * @param throwable What the phase-two command threw
   * @return Whether the cluster will answer the same way on every attempt
   */
  public static boolean permanentFailure(
      final Throwable throwable) {

    return anyCauseAnswers(
        throwable,
        // the task or instance key of the outbox entry is not a number, and it will not
        // become one
        cause -> (cause instanceof Camunda8RefusedStart) || (cause instanceof NumberFormatException) || ((cause instanceof ClientHttpException http) && PERMANENT_HTTP_STATUS
            .contains(http.code())) || ((cause instanceof ClientStatusException status) && PERMANENT_GRPC_CODES
                .contains(status.getStatusCode())));

  }

  /**
   * Whether repeating a command a JOB HANDLER sends back to the cluster - a completion, a
   * BPMN error, a failure, a lock renewal - can change its answer. It is
   * {@link #permanentFailure} plus the two cases which are permanent for a job command and
   * not for an outbox entry: a job which is gone stays gone, and a job another activation
   * holds is not going to be handed back. Repeating either would turn the benign
   * at-least-once residual into a retry storm which runs until the job's lock expires.
   * <p>
   * There is deliberately no separate opinion about what backpressure looks like. The
   * cluster answers it with <code>RESOURCE_EXHAUSTED</code> on gRPC and HTTP 503 on REST,
   * neither of which is permanent, so the classification the outbox already uses covers it
   * - and one classification cannot drift apart from itself.
   *
   * @param throwable What the command threw
   * @return Whether another attempt is worth making
   */
  public static boolean repeatableJobCommandFailure(
      final Throwable throwable) {

    return !jobAlreadyGone(throwable) && !jobHeldByAnotherActivation(throwable) && !permanentFailure(
        throwable);

  }

  /**
   * How the cluster named a rejection, in the few words a log line can carry: the code of
   * the transport it arrived on and the sentence the cluster wrote around it.
   * <p>
   * The classifications above read a code and nothing else, because a decision may not rest
   * on words the cluster is free to reword (see decision 16 in the repository's
   * DECISIONS.md). This one decides nothing. It is read by a reader, who wants both halves
   * of what came back and wants them without turning on a stack trace first.
   *
   * @param throwable What the command failed with
   * @return One phrase naming the rejection, never <code>null</code>
   */
  public static String rejection(
      final Throwable throwable) {

    final var rejected = firstCauseAnswering(
        throwable,
        cause -> (cause instanceof ClientHttpException) || (cause instanceof ClientStatusException));
    if (rejected instanceof ClientHttpException http) {
      return "HTTP %d, %s".formatted(Integer.valueOf(http.code()), inOneLine(http.reason(), http));
    }
    if (rejected instanceof ClientStatusException status) {
      return "gRPC %s, %s"
          .formatted(status.getStatusCode(), inOneLine(status.getStatus().getDescription(), status));
    }
    return incidentMessage(throwable);

  }

  /**
   * What the cluster wrote around a code, as one line: the reason the transport carries, and
   * the exception's message where it carries none. A problem detail arrives with line breaks
   * in it, and a log line which brings its own is a log line nothing greps.
   */
  private static String inOneLine(
      final String reason,
      final Throwable throwable) {

    final var words = (reason == null) || reason.isBlank()
        ? throwable.getMessage()
        : reason;
    return (words == null) || words.isBlank()
        ? "no reason given"
        : words.replaceAll("\\s+", " ").trim();

  }

  /**
   * What a failed job reports as its error message - the text an operator reads in the
   * incident, so it carries the exception's TYPE next to its message. Camunda's own advice
   * is that this message is what a human sees, and the plain message alone says
   * <code>null</code> for every failure which carries none, a
   * {@link NullPointerException} above all.
   *
   * @param throwable What the handler threw
   * @return The incident text, never <code>null</code>
   */
  public static String incidentMessage(
      final Throwable throwable) {

    if (throwable == null) {
      return "no exception given";
    }
    final var message = throwable.getMessage();
    return (message == null) || message.isBlank()
        ? throwable.getClass().getName()
        : "%s: %s".formatted(throwable.getClass().getName(), message);

  }

}
