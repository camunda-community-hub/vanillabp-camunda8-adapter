package io.vanillabp.camunda8.springboot.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the compensation integration test.
 *
 * <p>
 * Each of the two compensation handlers writes down when and on which thread it was entered,
 * and then waits a few seconds for the other one to be entered as well. Handlers which the
 * cluster hands out together meet each other at once. Handlers which it hands out one after
 * the other never meet: the first one gives up waiting and returns, and only then the second
 * one arrives.
 * </p>
 */
@Service
@WorkflowService(
    workflowAggregateClass = CompensationDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "CompensationProcess"))
public class CompensationDockerWorkflowService {

  /** How long a handler waits for the other one before it gives up and returns. */
  public static final long MEETING_WINDOW_MILLIS = 10_000;

  public static final String CANCEL_BOOKING = "cancelBooking";

  public static final String REFUND_PAYMENT = "refundPayment";

  /** What the handlers of one workflow did, as the test reads it. */
  public static final class Observation {

    private final CountDownLatch bothEntered = new CountDownLatch(2);

    private final AtomicInteger inside = new AtomicInteger();

    private final AtomicInteger mostInsideAtOnce = new AtomicInteger();

    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    private final List<String> entered = new ArrayList<>();

    private final List<String> threads = new ArrayList<>();

    public int attemptsOf(
        final String handler) {

      final var count = attempts.get(handler);
      return count == null ? 0 : count.get();

    }

    public int mostInsideAtOnce() {

      return mostInsideAtOnce.get();

    }

    public synchronized List<String> handlersInTheOrderTheyWereEntered() {

      return List.copyOf(entered);

    }

    public synchronized List<String> threadsTheHandlersRanOn() {

      return List.copyOf(threads);

    }

    private int enter(
        final String handler) throws InterruptedException {

      final var attempt = attempts
          .computeIfAbsent(handler, name -> new AtomicInteger())
          .incrementAndGet();
      synchronized (this) {
        entered.add(handler);
        threads.add(Thread.currentThread().getName());
      }
      final var nowInside = inside.incrementAndGet();
      mostInsideAtOnce.accumulateAndGet(nowInside, Math::max);
      if (attempt == 1) {
        bothEntered.countDown();
      }
      bothEntered.await(MEETING_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
      return attempt;

    }

    private void leave() {

      inside.decrementAndGet();

    }

  }

  /** One observation per workflow aggregate ID, inspected by the integration test. */
  public static final Map<Long, Observation> OBSERVATIONS = new ConcurrentHashMap<>();

  private final ProcessService<CompensationDockerAggregate> processService;

  public CompensationDockerWorkflowService(
      final ProcessService<CompensationDockerAggregate> processService) {

    this.processService = processService;

  }

  public static Observation observationOf(
      final Long aggregateId) {

    return OBSERVATIONS.computeIfAbsent(aggregateId, id -> new Observation());

  }

  public CompensationDockerAggregate startWorkflow(
      final boolean refundFailsOnce) {

    final var aggregate = new CompensationDockerAggregate();
    aggregate.setRefundFailsOnce(refundFailsOnce);
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void bookHotel(
      final CompensationDockerAggregate aggregate) {

    aggregate.setHotelBooked(Boolean.TRUE);

  }

  @WorkflowTask
  public void chargePayment(
      final CompensationDockerAggregate aggregate) {

    aggregate.setPaymentCharged(Boolean.TRUE);

  }

  @WorkflowTask
  public void cancelBooking(
      final CompensationDockerAggregate aggregate) throws InterruptedException {

    final var observation = observationOf(aggregate.getId());
    try {
      observation.enter(CANCEL_BOOKING);
      aggregate.setBookingCancelled(Boolean.TRUE);
    } finally {
      observation.leave();
    }

  }

  @WorkflowTask
  public void refundPayment(
      final CompensationDockerAggregate aggregate) throws InterruptedException {

    final var observation = observationOf(aggregate.getId());
    try {
      final var attempt = observation.enter(REFUND_PAYMENT);
      if (Boolean.TRUE.equals(aggregate.getRefundFailsOnce()) && (attempt == 1)) {
        throw new IllegalStateException("the payment provider is down, try again later");
      }
      aggregate.setPaymentRefunded(Boolean.TRUE);
    } finally {
      observation.leave();
    }

  }

  @WorkflowTask
  public void reportUndone(
      final CompensationDockerAggregate aggregate) {

    aggregate.setReported(Boolean.TRUE);

  }

}
