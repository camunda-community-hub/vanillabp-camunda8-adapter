package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientException;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ProblemException;
import io.grpc.Status;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which phase-two failures of Camunda 8 are worth repeating. The classification
 * is a pure function of the failure, so the boundary cases belong here and not into a test
 * against a cluster.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ErrorsTest {

  private static ProblemException problem(
      final int status) {

    return problem(status, "reason");
  }

  /**
   * A cluster's REST answer, as the client hands it on: the status twice (the HTTP code
   * and the problem detail's own field) and the rejection's name as the title.
   */
  private static ProblemException problem(
      final int status,
      final String title) {

    final var details = new ProblemDetail();
    details.setStatus(status);
    details.setTitle(title);
    return new ProblemException(status, "reason", details);
  }

  @Test
  @DisplayName("A request the cluster rejects is permanent")
  public void rejectedRequestsArePermanent() {

    assertTrue(Camunda8Errors.permanentFailure(problem(400)));
    assertTrue(Camunda8Errors.permanentFailure(problem(403)));
    assertTrue(Camunda8Errors.permanentFailure(problem(405)));
    assertTrue(Camunda8Errors.permanentFailure(problem(501)));
    // ProblemException needs no rule of its own - a plain HTTP failure counts as well
    assertTrue(Camunda8Errors.permanentFailure(new ClientHttpException(400, "Bad Request")));

  }

  @Test
  @DisplayName("The gRPC equivalents are permanent, too")
  public void grpcEquivalentsArePermanent() {

    assertTrue(
        Camunda8Errors.permanentFailure(
            new ClientStatusException(Status.INVALID_ARGUMENT, null)));
    assertTrue(
        Camunda8Errors.permanentFailure(
            new ClientStatusException(Status.PERMISSION_DENIED, null)));
    assertTrue(
        Camunda8Errors.permanentFailure(
            new ClientStatusException(Status.UNIMPLEMENTED, null)));

  }

  @Test
  @DisplayName("A task key which is not a number can never become one")
  public void malformedTaskKeyIsPermanent() {

    assertTrue(
        Camunda8Errors.permanentFailure(
            new IllegalStateException("dispatching", new NumberFormatException("not-a-key"))));

  }

  @Test
  @DisplayName("The two answers a refused start brings along are read from their codes")
  public void aRefusedStartIsRecognisedByItsCodes() {

    // the process is not deployed on this cluster, said by both transports
    assertTrue(Camunda8Errors.startRefusedForGood(problem(404, "NOT_FOUND")));
    assertTrue(
        Camunda8Errors.startRefusedForGood(
            new ClientStatusException(Status.NOT_FOUND, null)));
    // the model has no plain start event, which the REST transport answers with a
    // conflict. Camunda8RefusedStartIT is where both of them come from a cluster
    assertTrue(Camunda8Errors.startRefusedForGood(problem(409, "INVALID_STATE")));
    // whatever wrapped the refusal on the way out of the command must not hide it
    assertTrue(
        Camunda8Errors.startRefusedForGood(
            new IllegalStateException("starting failed", problem(404, "NOT_FOUND"))));

    // everything else a start can fail with keeps the answer the outbox gives anyway
    assertFalse(Camunda8Errors.startRefusedForGood(problem(503)));
    assertFalse(Camunda8Errors.startRefusedForGood(new IOException("connection reset")));

  }

  @Test
  @DisplayName("A start refused for good is permanent, and the same codes elsewhere are not")
  public void onlyAStartTurnsThoseCodesIntoAPermanentFailure() {

    // what the start throws when the cluster refused it: the refusal stays the cause and
    // the wrapping is what says which operation met it
    assertTrue(
        Camunda8Errors.permanentFailure(
            new Camunda8RefusedStart("the cluster does not hold that process", problem(404, "NOT_FOUND"))));
    assertTrue(
        Camunda8Errors.permanentFailure(
            new IllegalStateException(
                "dispatching failed", new Camunda8RefusedStart(
                    "the model has no plain start event", problem(409, "INVALID_STATE")))));

    // and the same answers without a start behind them stay what they were: a read of
    // something the exporter has not caught up with, and a conflict the outbox exists for
    assertFalse(Camunda8Errors.permanentFailure(problem(404, "NOT_FOUND")));
    assertFalse(Camunda8Errors.permanentFailure(problem(409, "INVALID_STATE")));

  }

  @Test
  @DisplayName("Eventual consistency, an expired token, conflicts and cluster trouble are repeated")
  public void everythingElseIsRepeatable() {

    // 404: the signature of eventual consistency (for job commands jobAlreadyGone
    // consumes it before the classification is asked at all)
    assertFalse(Camunda8Errors.permanentFailure(problem(404)));
    // 401: usually an expired token the client refreshes
    assertFalse(Camunda8Errors.permanentFailure(problem(401)));
    assertFalse(Camunda8Errors.permanentFailure(problem(409)));
    assertFalse(Camunda8Errors.permanentFailure(problem(429)));
    assertFalse(Camunda8Errors.permanentFailure(problem(500)));
    assertFalse(Camunda8Errors.permanentFailure(problem(503)));
    assertFalse(
        Camunda8Errors.permanentFailure(
            new ClientStatusException(Status.UNAVAILABLE, null)));
    assertFalse(Camunda8Errors.permanentFailure(new IOException("connection reset")));
    assertFalse(Camunda8Errors.permanentFailure(null));

  }

  @Test
  @DisplayName("A timeout is repeated, in every shape the client hands one over in")
  public void aTimeoutIsRepeatedInEveryShape() {

    // The transport decides how a request which ran out of time reaches the adapter, and
    // none of these shapes is a statement about what the cluster did - the answer may
    // have been given and only failed to arrive. So every one of them is repeated, and
    // none of them is mistaken for a job which is gone.
    for (final var timeout : timeoutsAsTheClientHandsThemOver()) {
      assertFalse(Camunda8Errors.permanentFailure(timeout), timeout.toString());
      assertFalse(Camunda8Errors.jobAlreadyGone(timeout), timeout.toString());
      assertTrue(Camunda8Errors.repeatableJobCommandFailure(timeout), timeout.toString());
      assertFalse(Camunda8Errors.messageAlreadyPublished(timeout), timeout.toString());
      assertFalse(Camunda8Errors.queryApiRefused(timeout), timeout.toString());
    }

  }

  /**
   * Every shape a request which ran out of time arrives in - read off the client: a REST
   * request times out in the socket below Apache HttpClient, a bounded wait on the future
   * ends in a {@link TimeoutException}, and gRPC answers a deadline of
   * its own with the status of that name. The client wraps whichever of them into a
   * {@link ClientException} respectively a
   * {@link CompletionException} on its way out.
   */
  private static List<Throwable> timeoutsAsTheClientHandsThemOver() {

    return List
        .of(
            new SocketTimeoutException("Read timed out"),
            new CompletionException(
                new ClientException(
                    "io error", new SocketTimeoutException("Read timed out"))),
            new CompletionException(new TimeoutException()),
            new ClientException(
                "timed out", new TimeoutException("waited 10s")),
            new ClientStatusException(Status.DEADLINE_EXCEEDED, null),
            new CompletionException(
                new ClientStatusException(Status.DEADLINE_EXCEEDED, null)));

  }

  @Test
  @DisplayName("The whole chain of causes is examined, and a self-referencing cause does not loop")
  public void theChainIsExaminedWithoutLooping() {

    assertTrue(
        Camunda8Errors.permanentFailure(
            new IllegalStateException("outer", new RuntimeException("inner", problem(400)))));

    final var selfReferencing = new RuntimeException("loops") {

      @Override
      public synchronized Throwable getCause() {
        return this;
      }

    };
    assertFalse(Camunda8Errors.permanentFailure(selfReferencing));

  }

  @Test
  @DisplayName("A job command has one more permanent case: the job itself is gone")
  public void aGoneJobIsPermanentForJobCommands() {

    // repeatable for an outbox entry (404 is the signature of eventual consistency) and
    // permanent for a command against THIS job - repeating it would turn the tolerated
    // at-least-once residual into a retry storm
    assertFalse(Camunda8Errors.permanentFailure(problem(404)));
    assertFalse(Camunda8Errors.repeatableJobCommandFailure(problem(404)));

  }

  @Test
  @DisplayName("And one more: a job another activation holds, on both transports")
  public void aJobSomebodyElseHoldsIsPermanentForJobCommands() {

    // measured on 8.10.0-alpha5: a completion carrying a superseded lease token, one
    // carrying none against a leased job, a failure and a BPMN error all answer this pair
    assertTrue(Camunda8Errors.jobHeldByAnotherActivation(problem(409, "INVALID_STATE")));
    assertTrue(
        Camunda8Errors.jobHeldByAnotherActivation(
            new ClientStatusException(Status.FAILED_PRECONDITION, null)));
    assertFalse(Camunda8Errors.repeatableJobCommandFailure(problem(409, "INVALID_STATE")));
    assertFalse(
        Camunda8Errors.repeatableJobCommandFailure(
            new ClientStatusException(Status.FAILED_PRECONDITION, null)));

    // the other wrong states of a job command answer 404 there, and a job which is gone
    // is a case of its own
    assertFalse(Camunda8Errors.jobHeldByAnotherActivation(problem(404, "NOT_FOUND")));
    assertFalse(
        Camunda8Errors.jobHeldByAnotherActivation(
            new ClientStatusException(Status.NOT_FOUND, null)));
    assertFalse(Camunda8Errors.jobHeldByAnotherActivation(problem(400, "INVALID_ARGUMENT")));
    assertFalse(Camunda8Errors.jobHeldByAnotherActivation(new IOException("connection reset")));

    // and nothing changes for an outbox entry: 409 is one of the answers it repeats for
    assertFalse(Camunda8Errors.permanentFailure(problem(409, "INVALID_STATE")));

  }

  @Test
  @DisplayName("Backpressure is repeatable on both transports")
  public void backpressureIsRepeatable() {

    // REST answers with 503 and the title the engine sends...
    assertTrue(Camunda8Errors.repeatableJobCommandFailure(problem(503)));
    // ...gRPC with the status of the same name, and neither of them is permanent
    assertTrue(
        Camunda8Errors.repeatableJobCommandFailure(
            new ClientStatusException(Status.RESOURCE_EXHAUSTED, null)));
    assertFalse(Camunda8Errors.repeatableJobCommandFailure(problem(400)));

  }

  @Test
  @DisplayName("A job which is gone is recognised on both transports, by their codes")
  public void aGoneJobIsRecognisedOnBothTransports() {

    // what the cluster answers a command addressing a job key it does not hold:
    // REST 404 with the title of the rejection, gRPC the status of the same name
    assertTrue(Camunda8Errors.jobAlreadyGone(problem(404, "NOT_FOUND")));
    assertTrue(
        Camunda8Errors.jobAlreadyGone(
            new ClientStatusException(Status.NOT_FOUND, null)));
    // the words around the code are the cluster's to reword, so they decide nothing
    assertFalse(Camunda8Errors.jobAlreadyGone(new IllegalStateException("no such job was NOT_FOUND")));
    assertFalse(Camunda8Errors.jobAlreadyGone(problem(409, "ALREADY_EXISTS")));

  }

  @Test
  @DisplayName("A cluster which does not hold what was addressed says so on the REST transport")
  public void aRestNotFoundIsRecognised() {

    // the answer as the REST client hands it on, with and without a problem detail
    assertTrue(Camunda8Errors.notFound(problem(404, "NOT FOUND")));
    assertTrue(Camunda8Errors.notFound(new ClientHttpException(404, "Not Found")));
    // and it is read from the code alone: another rejection is not this one, and neither
    // is a failure which only carries the words
    assertFalse(Camunda8Errors.notFound(problem(403, "FORBIDDEN")));
    assertFalse(Camunda8Errors.notFound(new IllegalStateException("404 not found")));
    assertFalse(Camunda8Errors.notFound(null));

  }

  @Test
  @DisplayName("A cluster which does not hold what was addressed says so on the gRPC transport")
  public void aGrpcNotFoundIsRecognised() {

    assertTrue(Camunda8Errors.notFound(new ClientStatusException(Status.NOT_FOUND, null)));
    assertFalse(
        Camunda8Errors.notFound(new ClientStatusException(Status.PERMISSION_DENIED, null)));

  }

  @Test
  @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName("A cause chain which closes into a ring is walked once, not forever")
  public void aRingOfCausesEndsTheWalk() {

    // two failures naming each other as their cause - a shape a client produces by
    // wrapping a failure it wrapped before, and one which a walk guarding only the
    // self-reference never leaves. Every classification reads such a chain, so all of
    // them are asked here
    final var outer = new IllegalStateException("the outer failure");
    final var inner = new IllegalStateException("the inner failure", outer);
    outer.initCause(inner);

    assertFalse(Camunda8Errors.notFound(outer));
    assertFalse(Camunda8Errors.jobAlreadyGone(outer));
    assertFalse(Camunda8Errors.messageAlreadyPublished(outer));
    assertFalse(Camunda8Errors.queryApiRefused(outer));
    assertFalse(Camunda8Errors.permanentFailure(outer));
    assertEquals(
        "java.lang.IllegalStateException: the outer failure",
        Camunda8Errors.rejection(outer));

  }

  @Test
  @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName("An answer inside a ring of causes is still the answer")
  public void aRingOfCausesStillCarriesItsAnswer() {

    final var wrapped = new IllegalStateException("the failure it wrapped before");
    final var rejected = new ClientHttpException("failed", 404, "Not Found", wrapped);
    wrapped.initCause(rejected);

    assertTrue(Camunda8Errors.notFound(wrapped));
    assertEquals("HTTP 404, Not Found", Camunda8Errors.rejection(wrapped));

  }

  @Test
  @DisplayName("Either transport's answer is found however deep the client wrapped it")
  public void aWrappedNotFoundIsRecognised() {

    // the client hands an asynchronous command's failure over wrapped, so the answer of
    // an inner cause is the answer of the whole
    assertTrue(
        Camunda8Errors
            .notFound(new CompletionException(new ClientException("failed", problem(404, "NOT FOUND")))));
    assertTrue(
        Camunda8Errors
            .notFound(
                new CompletionException(
                    new ClientException("failed", new ClientStatusException(Status.NOT_FOUND, null)))));

  }

  @Test
  @DisplayName("A message published twice is recognised on both transports, by their codes")
  public void aRepeatedPublicationIsRecognisedOnBothTransports() {

    // a publication carrying a message id the cluster still remembers is rejected as
    // ALREADY_EXISTS - which reaches the REST client as HTTP 409 and the gRPC client as
    // the status of that name. Both transports are in use: which one carries a command
    // is what 'prefer-rest-over-grpc' decides
    assertTrue(Camunda8Errors.messageAlreadyPublished(problem(409, "ALREADY_EXISTS")));
    assertTrue(
        Camunda8Errors.messageAlreadyPublished(
            new ClientStatusException(Status.ALREADY_EXISTS, null)));
    // and the words are not what says so: a reworded rejection keeps its code, while a
    // failure carrying the word and no code is not a rejection of the cluster at all
    assertFalse(
        Camunda8Errors.messageAlreadyPublished(
            new IllegalStateException("a message with that id has already been published")));
    assertFalse(Camunda8Errors.messageAlreadyPublished(problem(400, "INVALID_ARGUMENT")));
    assertFalse(Camunda8Errors.messageAlreadyPublished(null));

  }

  @Test
  @DisplayName("A refused query-API request is recognised by its status, not by its wording")
  public void aRefusedSearchIsRecognisedByItsStatus() {

    // every query endpoint of a cluster which cannot be searched answers 403, and the
    // problem detail says in prose only whether secondary storage is missing or the
    // credentials fall short - which is why the probe treats both the same
    assertTrue(Camunda8Errors.queryApiRefused(problem(403, "FORBIDDEN")));
    assertTrue(
        Camunda8Errors.queryApiRefused(
            new IllegalStateException("searching", problem(403, "FORBIDDEN"))));
    // a cluster which is merely unreachable says nothing about what it offers
    assertFalse(Camunda8Errors.queryApiRefused(new IOException("connection refused")));
    assertFalse(
        Camunda8Errors.queryApiRefused(
            new IllegalStateException("This endpoint requires a secondary storage, but none is set")));
    assertFalse(Camunda8Errors.queryApiRefused(problem(503)));

  }

  @Test
  @DisplayName("An incident names the exception's type where its message says nothing")
  public void anIncidentNamesTheType() {

    assertEquals(
        "java.lang.NullPointerException",
        Camunda8Errors.incidentMessage(new NullPointerException()));
    assertEquals(
        "java.lang.IllegalStateException: the connection pool is exhausted",
        Camunda8Errors.incidentMessage(new IllegalStateException("the connection pool is exhausted")));
    assertEquals("no exception given", Camunda8Errors.incidentMessage(null));

  }

  @Test
  @DisplayName("A rejection is named by the code it arrived on and the words around it")
  public void aRejectionCarriesItsCodeAndItsReason() {

    assertEquals(
        "HTTP 404, job not found",
        Camunda8Errors.rejection(new ClientHttpException("Failed with code 404", 404, "job not found")));
    assertEquals(
        "gRPC NOT_FOUND, no such job",
        Camunda8Errors
            .rejection(new ClientStatusException(Status.NOT_FOUND.withDescription("no such job"), null)));
    // a problem detail arrives with line breaks in it, and a log line which brings its own
    // is a log line nothing greps
    assertEquals(
        "HTTP 409, the message was published before",
        Camunda8Errors
            .rejection(
                new ClientHttpException("conflict", 409, "the message\n  was published\n  before")));
    // and a failure of neither transport still says what it was
    assertEquals(
        "java.lang.IllegalStateException: connection reset",
        Camunda8Errors.rejection(new IllegalStateException("connection reset")));

  }

  @Test
  @DisplayName("A job the cluster holds but nobody activated is told apart from a job which is gone")
  public void aDormantJobIsNotAJobWhichIsGone() {

    // measured on 8.8.37, 8.9.19 and 8.10.0-alpha5: a job in the queue, a job whose lock
    // ran out and a job with no retry left all answer this way
    assertTrue(Camunda8Errors.jobIsThereButNotActive(problem(400, "INVALID_ARGUMENT")));
    assertTrue(
        Camunda8Errors
            .jobIsThereButNotActive(new ClientHttpException("Failed with code 400", 400, "but it is not active")));
    assertTrue(
        Camunda8Errors
            .jobIsThereButNotActive(
                new ClientStatusException(
                    Status.INVALID_ARGUMENT.withDescription("but it is not active"), null)));
    // a key the cluster does not hold is the other answer, and it stays the other answer
    assertFalse(Camunda8Errors.jobIsThereButNotActive(problem(404)));
    assertFalse(
        Camunda8Errors.jobIsThereButNotActive(new ClientStatusException(Status.NOT_FOUND, null)));
    // and nothing else is this answer
    assertFalse(Camunda8Errors.jobIsThereButNotActive(problem(409)));
    assertFalse(Camunda8Errors.jobIsThereButNotActive(problem(503)));
    assertFalse(Camunda8Errors.jobIsThereButNotActive(new IllegalStateException("connection reset")));
    assertFalse(Camunda8Errors.jobIsThereButNotActive(null));

  }

  @Test
  @DisplayName("The answer is found however deep the client wrapped it, and a 404 below it wins")
  public void aDormantJobIsFoundDownTheCauseChain() {

    assertTrue(
        Camunda8Errors
            .jobIsThereButNotActive(
                new CompletionException(new ClientException("update failed", problem(400)))));
    // notFound wins wherever it stands in the chain: a command which addressed a key the
    // cluster does not hold is gone, whatever the client wrapped around it on the way up
    assertFalse(
        Camunda8Errors
            .jobIsThereButNotActive(
                new CompletionException(
                    new ClientException(
                        "update failed", new ClientException("retried", problem(404))))));

  }

  @Test
  @DisplayName("A user task refused about is told from one the cluster no longer has")
  public void aRefusalAboutAUserTaskIsToldFromAGoneOne() {

    // measured for a task standing in UPDATING and for a task whose 'updating' listener
    // denied the update, and in both cases the task was there to be refused about
    assertTrue(Camunda8Errors.refusedAboutAUserTaskItHolds(problem(409, "CONFLICT")));
    assertTrue(
        Camunda8Errors
            .refusedAboutAUserTaskItHolds(new ClientStatusException(Status.FAILED_PRECONDITION, null)));
    // 404 is the answer which means the task is gone, and it stays that answer
    assertFalse(Camunda8Errors.refusedAboutAUserTaskItHolds(problem(404)));
    assertFalse(
        Camunda8Errors.refusedAboutAUserTaskItHolds(new ClientStatusException(Status.NOT_FOUND, null)));
    // 400 is in the endpoint's list and no run has ever produced one for a user task, so
    // what it would mean is a guess - and a guess here cancels an open task
    assertFalse(Camunda8Errors.refusedAboutAUserTaskItHolds(problem(400, "INVALID_ARGUMENT")));

  }

  @Test
  @DisplayName("A command refused about an instance the engine holds is told from one it forgot")
  public void aRefusalAboutAnInstanceIsToldFromAForgottenKey() {

    // the modification of the probe names an element the model does not have
    assertTrue(Camunda8Errors.refusedAboutAnInstanceItHolds(problem(400, "INVALID_ARGUMENT")));
    assertTrue(
        Camunda8Errors
            .refusedAboutAnInstanceItHolds(new ClientStatusException(Status.INVALID_ARGUMENT, null)));
    // and the business id assignment meets an instance which already carries one
    assertTrue(Camunda8Errors.refusedAboutAnInstanceItHolds(problem(409, "INVALID_STATE")));
    assertTrue(
        Camunda8Errors
            .refusedAboutAnInstanceItHolds(new ClientStatusException(Status.FAILED_PRECONDITION, null)));
    // a key the engine does not hold is the other answer, and it stays the other answer
    assertFalse(Camunda8Errors.refusedAboutAnInstanceItHolds(problem(404)));
    assertFalse(
        Camunda8Errors.refusedAboutAnInstanceItHolds(new ClientStatusException(Status.NOT_FOUND, null)));
    // an expired token and a missing permission are refusals which say nothing about the
    // instance, which is why the codes are spelled out rather than read as "not a 404"
    assertFalse(Camunda8Errors.refusedAboutAnInstanceItHolds(problem(401)));
    assertFalse(Camunda8Errors.refusedAboutAnInstanceItHolds(problem(403)));
    assertFalse(Camunda8Errors.refusedAboutAnInstanceItHolds(problem(503)));
    assertFalse(Camunda8Errors.refusedAboutAnInstanceItHolds(new IllegalStateException("connection reset")));

  }

}
