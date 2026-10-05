package io.vanillabp.camunda8.client;

/**
 * A start the cluster refused in a way it repeats on every attempt.
 * <p>
 * The classification the outbox asks for sees the failure and nothing else
 * ({@code MigratableProcessService#isPhaseTwoFailureRepeatable}), while two of the
 * cluster's answers mean one thing for a start and another for everything else: a
 * <code>404</code> is a process nobody deployed here when a start meets it, and the
 * exporter lagging behind when a read does, and a <code>409</code> is a model without a
 * plain start event here, while a publication meets it as a message which still lives.
 * So the operation travels INSIDE the failure: the start wraps what the cluster answered
 * into this exception, and {@link Camunda8Errors#permanentFailure(Throwable)} calls what
 * carries it permanent without saying anything about the same code elsewhere.
 * <p>
 * Which answers are wrapped is {@link Camunda8Errors#startRefusedForGood(Throwable)}, and
 * what the cluster really said stays the cause.
 */
public class Camunda8RefusedStart extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Reports a start the cluster refused for good, carrying the cluster's own answer as the
   * cause.
   *
   * @param message What an operator reads next to the blocked outbox entry
   * @param refusal What the cluster answered the create command with
   */
  public Camunda8RefusedStart(
      final String message,
      final Throwable refusal) {

    super(message, refusal);

  }

}
