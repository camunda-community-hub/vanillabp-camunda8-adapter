package io.vanillabp.camunda8.analysis895;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the cluster busy: starts a workflow every {@code a895.start-every}, and moves every
 * running one on, one operation at a time, the way an application would.
 * <p>
 * Every operation runs in a transaction of its own, like the call of a REST endpoint. Where
 * phase one throws, the transaction rolls back, the failure is written to
 * {@code A895_FAILURE} and the same operation is tried again on the next round - which is
 * the only repair the measure of analysis 895 allows. The next operation of a workflow
 * waits until the outbox holds no OPEN entry of that workflow any more, so a lost operation
 * cannot hide behind a later one which carries the same values.
 * <p>
 * {@code a895.delay} (ISO duration, default PT0S) is how long a workflow waits on a timer
 * between 'work' and the subprocess.
 * <p>
 * The file {@code control/starts-off} in the working directory switches the starts off, so
 * the run can drain.
 */
@Component
public class LoadDriver {

  private static final Logger LOG = LoggerFactory.getLogger("A895");

  /**
   * How far the driver brought a workflow.
   */
  public enum Stage {
    STARTED,
    PUSHED_GLOBAL,
    PUSHED_TASK,
    CHECK_COMPLETED,
    APPROVED,
    CORRELATED
  }

  private final ReadModelWorkflow workflow;

  private final ReadModelAggregateRepository repository;

  private final TransactionTemplate transactions;

  private final JdbcTemplate jdbc;

  private final Path startsOff;

  private final String delay;

  /**
   * @param workflow The workflow service
   * @param repository The aggregates
   * @param transactions The transactions every operation runs in
   * @param jdbc For the outbox and the bookkeeping tables
   * @param controlDirectory Where the switch file is looked for
   */
  public LoadDriver(
      final ReadModelWorkflow workflow,
      final ReadModelAggregateRepository repository,
      final TransactionTemplate transactions,
      final JdbcTemplate jdbc,
      @Value("${a895.control-directory:control}") final String controlDirectory,
      @Value("${a895.delay:PT0S}") final String delay) {

    this.workflow = workflow;
    this.repository = repository;
    this.transactions = transactions;
    this.jdbc = jdbc;
    this.startsOff = Path.of(controlDirectory, "starts-off");
    this.delay = delay;
    jdbc
        .execute("CREATE TABLE IF NOT EXISTS A895_EFFECT (AGGREGATE_ID BIGINT, EFFECT VARCHAR(64), "
            + "TASK_ID VARCHAR(64), AT_MILLIS BIGINT)");
    jdbc
        .execute("CREATE TABLE IF NOT EXISTS A895_FAILURE (AGGREGATE_ID BIGINT, OPERATION VARCHAR(64), "
            + "EXCEPTION VARCHAR(255), MESSAGE VARCHAR(4000), AT_MILLIS BIGINT, TOOK_MILLIS BIGINT)");
    jdbc
        .execute("CREATE TABLE IF NOT EXISTS A895_OPERATION (AGGREGATE_ID BIGINT, OPERATION VARCHAR(64), "
            + "AT_MILLIS BIGINT, TOOK_MILLIS BIGINT)");

  }

  /**
   * Starts one workflow, unless the starts are switched off.
   */
  @Scheduled(fixedRateString = "${a895.start-every:1500}", initialDelay = 5000)
  public void start() {

    if (Files.exists(startsOff)) {
      return;
    }
    run(null, "start", aggregateId -> {
      final var aggregate = new ReadModelAggregate();
      aggregate.setStage(Stage.STARTED.name());
      aggregate.setStartedAt(System.currentTimeMillis());
      aggregate.setDelay(delay);
      workflow.processService().startWorkflow(aggregate);
    });

  }

  /**
   * Moves every running workflow on by at most one operation.
   */
  @Scheduled(fixedDelayString = "${a895.advance-every:1000}", initialDelay = 5000)
  public void advance() {

    final var running = repository
        .findByStageIn(
            List
                .of(
                    Stage.STARTED.name(),
                    Stage.PUSHED_GLOBAL.name(),
                    Stage.PUSHED_TASK.name(),
                    Stage.CHECK_COMPLETED.name(),
                    Stage.APPROVED.name()));
    for (final var candidate : running) {
      final var id = candidate.getId();
      final var open = jdbc
          .queryForObject(
              "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX WHERE AGGREGATE_ID = ? AND STATUS = 'OPEN'",
              Integer.class,
              String.valueOf(id));
      if ((open != null) && (open > 0)) {
        continue;
      }
      switch (Stage.valueOf(candidate.getStage())) {
        case STARTED -> {
          if (candidate.getCheckTaskId() != null) {
            run(id, "aggregateChanged-global", aggregateId -> {
              final var aggregate = repository.findById(aggregateId).orElseThrow();
              aggregate.setGlobalMarker("g-"
                  + aggregateId);
              aggregate.setStage(Stage.PUSHED_GLOBAL.name());
              workflow.processService().aggregateChanged(aggregate);
            });
          }
        }
        case PUSHED_GLOBAL -> run(id, "aggregateChanged-task", aggregateId -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          aggregate.setTaskMarker("t-"
              + aggregateId);
          aggregate.setStage(Stage.PUSHED_TASK.name());
          workflow.processService().aggregateChanged(aggregate, aggregate.getCheckTaskId());
        });
        case PUSHED_TASK -> run(id, "completeTask", aggregateId -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          aggregate.setStage(Stage.CHECK_COMPLETED.name());
          workflow.processService().completeTask(aggregate, aggregate.getCheckTaskId());
        });
        case CHECK_COMPLETED -> {
          if (candidate.getApproveTaskId() != null) {
            run(id, "completeUserTask", aggregateId -> {
              final var aggregate = repository.findById(aggregateId).orElseThrow();
              aggregate.setStage(Stage.APPROVED.name());
              workflow.processService().completeUserTask(aggregate, aggregate.getApproveTaskId());
            });
          }
        }
        case APPROVED -> run(id, "correlateMessage", aggregateId -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          aggregate.setStage(Stage.CORRELATED.name());
          workflow.processService().correlateMessage(aggregate, "Proceed895");
        });
        default -> {
          // CORRELATED: the rest is the cluster's and the handlers' business
        }
      }
    }

  }

  private void run(
      final Long aggregateId,
      final String operation,
      final Consumer<Long> body) {

    final var startedAt = System.currentTimeMillis();
    try {
      transactions.executeWithoutResult(status -> body.accept(aggregateId));
      final var took = System.currentTimeMillis() - startedAt;
      jdbc
          .update(
              "INSERT INTO A895_OPERATION (AGGREGATE_ID, OPERATION, AT_MILLIS, TOOK_MILLIS) VALUES (?, ?, ?, ?)",
              aggregateId,
              operation,
              startedAt,
              took);
      if (took > 2000) {
        LOG.info("A895 slow {} of aggregate {} took {} ms", operation, aggregateId, took);
      }
    } catch (final RuntimeException e) {
      final var took = System.currentTimeMillis() - startedAt;
      final var message = String.valueOf(e.getMessage());
      jdbc
          .update(
              "INSERT INTO A895_FAILURE (AGGREGATE_ID, OPERATION, EXCEPTION, MESSAGE, AT_MILLIS, TOOK_MILLIS) "
                  + "VALUES (?, ?, ?, ?, ?, ?)",
              aggregateId,
              operation,
              e.getClass().getName(),
              message.length() > 3900 ? message.substring(0, 3900) : message,
              startedAt,
              took);
      LOG.warn("A895 {} of aggregate {} failed after {} ms: {}: {}", operation, aggregateId, took, e
          .getClass()
          .getSimpleName(), message.length() > 300 ? message.substring(0, 300) : message);
    }

  }

}
