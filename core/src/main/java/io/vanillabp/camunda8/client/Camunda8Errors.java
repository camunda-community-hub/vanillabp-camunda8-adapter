package io.vanillabp.camunda8.client;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ProblemException;
import io.grpc.Status;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;

/**
 * Shared classification of Camunda 8 client errors. Two kinds of caller ask.
 * <p>
 * A caller which SENT something asks whether another attempt can change the answer. One
 * question is whether a job-based command failed because the job is GONE (already
 * completed, canceled by a boundary event, or the workflow moved on). That is the small
 * rest of at-least-once delivery. Completions accept it, and awareness probes map it to
 * UNKNOWN_TO_BPMS. The other question is whether a phase-two operation is refused the
 * same way every time. Everything else counts as an infrastructure failure.
 * <p>
 * A caller which READ something asks what the cluster's answer means about the state it
 * asked for. {@link #notFound(Throwable)} is the reading side of the same code. There it
 * usually means that the exporter has not caught up yet, not a failure. This class is
 * public for that side.
 * <p>
 * Every question is answered from the codes of the two transports, along the cause chain
 * of the failure. It is never answered from the words the cluster wraps around them. Why
 * a job which is gone is final even though the classification is otherwise generous is
 * decision 9 in the repository's DECISIONS.md.
 */
public final class Camunda8Errors {

  private Camunda8Errors() {
  }

  /**
   * The first failure in the chain of causes which answers the question, or
   * <code>null</code> where none does.
   * <p>
   * Every classification here reads a cause chain, and a cause chain does not always end.
   * A client which wraps a failure it already wrapped hands over a ring. The walk would
   * then run forever inside a job handler or an outbox dispatch. That is worse than any
   * answer it could have given. So the walk remembers the failures it has seen, by identity
   * and not by <code>equals</code>. It ends when it sees one of them a second time. This
   * works for a self reference and for a longer ring alike.
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
   * Whether any failure in the chain of causes answers the question. The walk is bounded
   * as {@link #firstCauseAnswering(Throwable, Predicate)} describes.
   */
  private static boolean anyCauseAnswers(
      final Throwable throwable,
      final Predicate<Throwable> question) {

    return firstCauseAnswering(throwable, question) != null;

  }

  /**
   * Whether the cluster answered "I do not hold that". It is the one answer which means
   * that the addressed key is not there, whatever the key addressed.
   * <p>
   * Both transports say it with a code of their own. The REST gateway answers with HTTP
   * <code>404</code> (a {@link ProblemException} carries it as well, because it extends
   * {@link ClientHttpException}). The gRPC gateway answers with the status
   * <code>NOT_FOUND</code>. Nothing here reads the message text. The cluster is free to
   * change the words it wraps around that code.
   * <p>
   * It is public because the answer means different things to different callers, and
   * each of them has to recognise it first. For a job command it is a job which is gone
   * (see {@link #jobAlreadyGone(Throwable)}). For a read of something just written, the
   * exporter has not caught up yet. If either reads only ONE of the two codes, the answer
   * of the other transport turns into a hard failure.
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
   * A job command addresses one key and nothing else. So if the cluster does not hold that
   * key, that is the whole answer. This method reads {@link #notFound(Throwable)} and adds
   * the meaning: the job was completed, canceled or has moved on in some other way.
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
   * right now. This answer says that the BPMS still holds the task.
   * <p>
   * Measured on 8.8.37, 8.9.19 and 8.10.0-alpha5, with the same answer on all three. An
   * <code>UpdateJobTimeout</code> against a job which is not locked is refused with HTTP
   * <code>400</code>, on gRPC with <code>INVALID_ARGUMENT</code>. Three situations cause it,
   * and the BPMS holds the task in all three. The job waits in the queue and was never
   * activated. Or its lock ran out and it went back into the queue. Or it failed with no
   * retry left, and an incident is open on it.
   * <p>
   * The middle one happens in normal operation. An asynchronous task keeps its job locked
   * for <code>async-task-lock-renewal</code>. When that hour passes, the cluster puts the
   * job back into the queue until a worker takes it again. If the caller reads that gap as
   * an outage, it retries for a task which is perfectly alive.
   * <p>
   * The CODE alone carries the meaning here, so decision 16 in the repository's DECISIONS.md
   * needs no widening. A job command names one key. The cluster answers a key it does not
   * hold with {@link #notFound(Throwable)}. So any other refusal is about a job the cluster
   * HAS. Which of the three situations it is does not change the answer. So the sentence
   * the cluster writes around the code is not read.
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
   * job. A run whose lock had expired meets this answer.
   * <p>
   * A worker of this adapter may lease its activations (see decision 36 in the
   * repository's DECISIONS.md). Then the cluster takes the completion, the failure and the
   * BPMN error of a job only from whoever carries the token of the CURRENT activation. An
   * activation which followed an expired lock replaces the token before it. So the run
   * which finished first is refused. The workflow goes on with the values of the run which
   * finished last.
   * <p>
   * Measured on 8.10.0-alpha5: a completion with a replaced token is refused with HTTP
   * <code>409</code>, title <code>INVALID_STATE</code>, and on gRPC with
   * <code>FAILED_PRECONDITION</code>. A completion with no token at all against a leased
   * job gets the same pair. A failure and a BPMN error get the same answer. Every other
   * wrong state of a job command measured there is a <code>404</code>: a key which never
   * existed, a job which is already completed, and a token handed to a job which has no
   * lease.
   * <p>
   * The code alone cannot say WHICH wrong state the cluster means. The REST specification
   * says that 409 is "the job is in the wrong state", and nothing narrower. The cluster is
   * free to reword the sentence around it (decision 16 in the repository's DECISIONS.md).
   * So this adapter follows the honest rule: a 409 of a JOB command means that somebody
   * else holds this activation, whatever the reason. Both readings end the same way. The
   * command is not repeated, because no repetition of it would be accepted either.
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
   * Whether the cluster refused a publication because a message with the same id was
   * published before and still lives. This answer makes an outbox entry done, and it is not
   * repeated, because a repetition would be refused again.
   * <p>
   * Both transports name the rejection with a code, and this adapter uses both. A
   * publication goes over REST or gRPC, depending on
   * <code>vanillabp.adapters.&lt;id&gt;.prefer-rest-over-grpc</code>. On gRPC the
   * rejection arrives as the status <code>ALREADY_EXISTS</code>, on REST as HTTP
   * <code>409</code> (whose problem detail has the same word as its title). No other
   * conflict reaches a publication, so the code alone decides it. The cluster stays free
   * to reword the sentence around it.
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
   * Whether the cluster refused a command ABOUT AN INSTANCE IT HOLDS. With this answer, a
   * command which is refused on purpose becomes a question: does the engine have that
   * instance at all?
   * <p>
   * An engine addressed by an instance key answers a key it does not hold with
   * {@link #notFound(Throwable)}. It forgets an instance the moment the instance ends. So
   * everything it refuses for a reason of its own is about an instance it HAS. A
   * modification which names an element the model does not have is rejected with HTTP
   * <code>400</code> (on gRPC <code>INVALID_ARGUMENT</code>). A business id assigned to an
   * instance which already has one is rejected with HTTP <code>409</code> (on gRPC
   * <code>FAILED_PRECONDITION</code>).
   * <p>
   * The list is spelled out, and not written as "anything which is not a 404". An expired
   * token and a missing permission are refusals too, and they say nothing about the
   * instance. Which command is sent, and why both of them are refused and not carried out,
   * is decision 35 in the repository's DECISIONS.md.
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
   * Whether the cluster refused a command ABOUT A USER TASK IT HOLDS. With this answer,
   * the empty update of {@link Camunda8UserTaskProbe} becomes a question about whether the
   * task is still open. It also keeps a refused completion from being read as a task which
   * is gone.
   * <p>
   * A user task the cluster no longer has is answered with {@link #notFound(Throwable)}.
   * HTTP <code>409</code> (on gRPC <code>FAILED_PRECONDITION</code>) is something else. It
   * was measured for three states of a task the cluster was holding. The task was in
   * <code>UPDATING</code>. Or the modelled <code>updating</code> listener of the task denied
   * the update. Or the task was in <code>CREATING</code>, because one of its
   * <code>creating</code> listeners had not been answered yet. The third one is the everyday
   * case, because VanillaBP tells the application FROM such a listener. An application
   * which completes its user task right away addresses a task the cluster is still
   * creating. While that lasts, both the empty update and the completion get
   * <code>409</code>.
   * <p>
   * HTTP <code>400</code> is NOT in here, on purpose, although the endpoint documents it.
   * No run has ever produced one for a user task. So what it would mean is a guess. A guess
   * in this direction turns an open task into a canceled one.
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
   * Whether the cluster REFUSED a query-API request. A cluster which cannot be searched at
   * all does that.
   * <p>
   * The searches of this adapter go over REST only, because the client offers no gRPC
   * equivalent for them. The cluster refuses them with HTTP <code>403</code>. That code
   * does not say WHY, see {@link Camunda8QueryApi}. So only the probe asks this question,
   * and everything else reads the remembered answer.
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
   * Whether the cluster refused to START a workflow in a way which every further attempt
   * meets again. Measured against a cluster of the tested line on 2026-09-11, over both
   * transports where both could be asked:
   * <ul>
   * <li>The process is not deployed on this cluster. That is HTTP <code>404</code> with
   * the title <code>NOT_FOUND</code>, on gRPC the status <code>NOT_FOUND</code>.</li>
   * <li>The model has no plain start event. So nothing can be started without the message
   * or the timer the model asks for. That is HTTP <code>409</code> with the title
   * <code>INVALID_STATE</code>.</li>
   * </ul>
   * Neither of them goes away while the application waits. A deployment reaches the
   * cluster before the outbox dispatches anything. A model is changed by a deployment and
   * never by a repetition. So the start behind such an answer is better parked where an
   * operator finds it. Otherwise a start whose aggregate is committed would be repeated for
   * hours.
   * <p>
   * The gRPC equivalent of the second one is not in here, because it could not be
   * measured. The cluster the tests run against answers a gRPC create of a deployed
   * process by refusing the permission, which is permanent for other reasons. Only what
   * was measured is classified. So an installation on gRPC which meets a model without a
   * plain start event still pays the full row of attempts.
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
   * HTTP statuses of the REST transport which a repetition cannot change:
   * <ul>
   * <li><code>400</code>: the cluster rejected the request itself.</li>
   * <li><code>403</code>: the credentials or the tenant are wrong, not late.</li>
   * <li><code>405</code> and <code>501</code>: this cluster version has no such
   * endpoint.</li>
   * </ul>
   * NOT in the list, on purpose: <code>404</code> is the typical sign of eventual
   * consistency (and for job commands it never gets here, see
   * {@link #jobAlreadyGone(Throwable)}). <code>401</code> is usually an expired token which
   * the client refreshes. And <code>409</code>, <code>429</code> and every <code>5xx</code>
   * are exactly what the outbox repeats for. Two of them mean something else when a START
   * meets them. That is why a start brings its own answer along, see
   * {@link Camunda8RefusedStart}.
   */
  private static final Set<Integer> PERMANENT_HTTP_STATUS = Set.of(400, 403, 405, 501);

  /**
   * The gRPC equivalents of {@link #PERMANENT_HTTP_STATUS}, for the commands which still
   * use that transport. <code>NOT_FOUND</code>, <code>UNAUTHENTICATED</code>,
   * <code>ABORTED</code>, <code>RESOURCE_EXHAUSTED</code> and
   * <code>UNAVAILABLE</code> stay repeatable for the reasons given there.
   */
  private static final Set<Status.Code> PERMANENT_GRPC_CODES = Set
      .of(
          Status.Code.INVALID_ARGUMENT,
          Status.Code.PERMISSION_DENIED,
          Status.Code.UNIMPLEMENTED);

  /**
   * Whether a repetition cannot help a phase-two operation which failed like this. Then
   * the outbox entry is blocked at once. It is not retried until its attempts are used up.
   * <p>
   * The list is short on purpose. Repeating is the safe answer, and it stays the default
   * for everything not named here. {@link ProblemException} needs no rule of its own,
   * because it extends {@link ClientHttpException}.
   * <p>
   * One operation adds an answer of its own, because the same code means different things
   * depending on what was sent. A start which the cluster refused for good wraps the
   * refusal into a {@link Camunda8RefusedStart}. For the operations which did not send a
   * start, nothing about those codes changes.
   *
   * @param throwable What the phase-two command threw
   * @return Whether the cluster will answer the same way on every attempt
   */
  public static boolean permanentFailure(
      final Throwable throwable) {

    return anyCauseAnswers(
        throwable,
        // the task or instance key of the outbox entry is not a number, and it will never
        // become one
        cause -> (cause instanceof Camunda8RefusedStart) || (cause instanceof NumberFormatException) || ((cause instanceof ClientHttpException http) && PERMANENT_HTTP_STATUS
            .contains(http.code())) || ((cause instanceof ClientStatusException status) && PERMANENT_GRPC_CODES
                .contains(status.getStatusCode())));

  }

  /**
   * Whether a repetition can change the answer to a command which a JOB HANDLER sends back
   * to the cluster. That is a completion, a BPMN error, a failure or a lock renewal. It is
   * {@link #permanentFailure} plus two cases which are permanent for a job command, but not
   * for an outbox entry. A job which is gone stays gone. A job which another activation
   * holds is not going to be handed back. A repetition of either would turn the harmless
   * rest of at-least-once delivery into a storm of retries, until the lock of the job
   * expires.
   * <p>
   * On purpose, there is no separate opinion about what backpressure looks like. The
   * cluster answers it with <code>RESOURCE_EXHAUSTED</code> on gRPC and HTTP 503 on REST.
   * Neither of them is permanent, so the classification the outbox already uses covers
   * it. And one classification cannot drift apart from itself.
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
   * How the cluster named a rejection, in the few words a log line can carry. It gives the
   * code of the transport it arrived on and the sentence the cluster wrote around it.
   * <p>
   * The classifications above read a code and nothing else. A decision must not rest on
   * words which the cluster is free to reword (see decision 16 in the repository's
   * DECISIONS.md). This one decides nothing. A person reads it, and wants both halves of
   * what came back, without turning on a stack trace first.
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
   * What the cluster wrote around a code, as one line. It is the reason the transport
   * carries, or the message of the exception where the transport carries none. A problem
   * detail arrives with line breaks in it. A log line with line breaks of its own is one
   * which no grep finds.
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
   * What a failed job reports as its error message. An operator reads this text in the
   * incident, so it carries the TYPE of the exception next to its message. Camunda's own
   * advice is that a human sees this message. The plain message alone says
   * <code>null</code> for every failure which has none, above all for a
   * {@link NullPointerException}.
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

  /**
   * The longest incident text every secondary storage of the cluster keeps whole: 4000
   * characters, and on Oracle 4000 bytes. The cluster itself takes a longer text, but the
   * storage which Operate and the search API read from cuts it. Decision 81 in the
   * repository's DECISIONS.md says where the number comes from.
   */
  public static final int INCIDENT_MESSAGE_LIMIT = 4000;

  /**
   * What a failed job reports as its error message, with the whole stack trace where the
   * application asked for it with <code>report-errors-as-stack-trace</code> (see decision 81
   * in the repository's DECISIONS.md).
   * <p>
   * The text starts with the chain of causes, one line per level: the type and the message,
   * the innermost cause last. The chain takes at most half of
   * {@link #INCIDENT_MESSAGE_LIMIT}. The stack trace follows. Where the whole text is longer
   * than the limit, this method cuts the END of the stack trace. So the innermost cause is
   * always in the incident. A Spring exception wraps its cause in several layers of
   * frames, and a cut by the cluster would take away exactly the cause.
   * <p>
   * A {@link DeliveryOfAnUnknownWorkflowException} gets no stack trace. It is a refusal of
   * the core, not a defect of the code which ran. Its message names the workflow and says
   * what to do, and the trace would only add which line of the core read the database.
   *
   * @param throwable What the handler threw
   * @param withStackTrace Whether the application asked for the stack trace
   * @return The incident text, never <code>null</code>
   */
  public static String incidentMessage(
      final Throwable throwable,
      final boolean withStackTrace) {

    if (!withStackTrace || (throwable == null) || (throwable instanceof DeliveryOfAnUnknownWorkflowException)) {
      return incidentMessage(throwable);
    }
    final var stackTrace = new StringWriter();
    throwable.printStackTrace(new PrintWriter(stackTrace));
    return cutToTheLimit(chainOfCauses(throwable)
        + "\n\n"
        + stackTrace);

  }

  /**
   * The longest line of {@link #chainOfCauses(Throwable)}. A message can be very long, for
   * example one which repeats the messages of all its causes. One such line must not push
   * the innermost cause out of the incident.
   */
  private static final int CAUSE_LINE_LIMIT = 300;

  /**
   * What starts every line of {@link #chainOfCauses(Throwable)} after the first one. It is
   * the words Java itself uses in a stack trace.
   */
  private static final String CAUSED_BY = "\nCaused by: ";

  /**
   * The room kept for the line which says how many causes were left out.
   */
  private static final int LEFT_OUT_LINE = 40;

  /**
   * The chain of causes, one line per level, the innermost cause last. It takes at most half
   * of {@link #INCIDENT_MESSAGE_LIMIT}, so the stack trace keeps the other half. Where the
   * chain is longer, the outermost cause stays, and so do as many of the innermost ones as
   * fit. One line in between says how many causes were left out.
   */
  private static String chainOfCauses(
      final Throwable throwable) {

    final var causes = new ArrayList<String>();
    // bounded like every other walk of a cause chain here, see firstCauseAnswering
    final var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    var current = throwable;
    while ((current != null) && seen.add(current)) {
      final var line = incidentMessage(current).replaceAll("\\s+", " ");
      causes.add(line.length() <= CAUSE_LINE_LIMIT
          ? line
          : line.substring(0, CAUSE_LINE_LIMIT - 4)
              + " ...");
      current = current.getCause();
    }
    final var budget = INCIDENT_MESSAGE_LIMIT / 2;
    final var innermostKept = new ArrayDeque<String>();
    var used = causes.getFirst().length() + LEFT_OUT_LINE;
    var index = causes.size() - 1;
    while ((index > 0) && ((used + CAUSED_BY.length() + causes.get(index).length()) <= budget)) {
      used += CAUSED_BY.length() + causes.get(index).length();
      innermostKept.addFirst(causes.get(index));
      index--;
    }
    final var text = new StringBuilder(causes.getFirst());
    if (index > 0) {
      text
          .append(CAUSED_BY)
          .append("... ")
          .append(index)
          .append(index == 1
              ? " cause left out"
              : " causes left out");
    }
    innermostKept.forEach(cause -> text
        .append(CAUSED_BY)
        .append(cause));
    return text.toString();

  }

  /**
   * The text, cut at its end so that it takes at most {@link #INCIDENT_MESSAGE_LIMIT} bytes in
   * UTF-8, with a last line which says that it was cut. A character never takes less than one
   * byte, so the text then also has at most that many characters.
   */
  static String cutToTheLimit(
      final String text) {

    if (text.getBytes(StandardCharsets.UTF_8).length <= INCIDENT_MESSAGE_LIMIT) {
      return text;
    }
    final var cutNote = "\n... cut by VanillaBP to the %d characters the cluster keeps"
        .formatted(Integer.valueOf(INCIDENT_MESSAGE_LIMIT));
    final var room = INCIDENT_MESSAGE_LIMIT - cutNote.length();
    // walked by code point, so a character which needs two chars is never cut in half. Half
    // of it would turn into a question mark
    var end = 0;
    var bytes = 0;
    while (end < text.length()) {
      final var codePoint = text.codePointAt(end);
      final var size = utf8Bytes(codePoint);
      if (bytes + size > room) {
        break;
      }
      bytes += size;
      end += Character.charCount(codePoint);
    }
    return text.substring(0, end) + cutNote;

  }

  private static int utf8Bytes(
      final int codePoint) {

    if (codePoint < 0x80) {
      return 1;
    }
    if (codePoint < 0x800) {
      return 2;
    }
    return codePoint < 0x10000
        ? 3
        : 4;

  }


}
