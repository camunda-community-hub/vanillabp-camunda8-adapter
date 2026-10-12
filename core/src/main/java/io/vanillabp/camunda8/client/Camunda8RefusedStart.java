package io.vanillabp.camunda8.client;

/**
 * A start the cluster refused in a way it repeats on every attempt.
 * <p>
 * The classification the outbox asks for sees the failure and nothing else
 * ({@code MigratableProcessService#isPhaseTwoFailureRepeatable}). But two answers of the
 * cluster mean one thing for a start and another thing for everything else. When a start
 * meets a <code>404</code>, nobody deployed the process here. When a read meets it, the
 * exporter is behind. A <code>409</code> here is a model without a plain start event. A
 * publication meets it as a message which still lives. So the operation travels INSIDE
 * the failure. The start wraps what the cluster answered into this exception.
 * {@link Camunda8Errors#permanentFailure(Throwable)} calls a failure which carries it
 * permanent, and says nothing about the same code elsewhere.
 * <p>
 * {@link Camunda8Errors#startRefusedForGood(Throwable)} decides which answers are wrapped.
 * What the cluster really said stays the cause.
 */
public class Camunda8RefusedStart extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Reports a start which the cluster refused for good. The cluster's own answer is the
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
