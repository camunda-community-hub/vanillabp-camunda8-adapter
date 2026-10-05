package io.vanillabp.camunda8.wiring;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import lombok.extern.slf4j.Slf4j;

/**
 * The retry backoff a BPMN model carries on a single element, in the task header
 * {@value #HEADER_NAME}. Camunda 7 lets a model say per task how long the engine waits
 * before the next attempt ({@code camunda:failedJobRetryTimeCycle}), Camunda 8 knows no
 * such attribute, and VanillaBP 1 closed that gap with this header. Applications have it
 * in their models, so it is read again.
 * <p>
 * <b>Read at delivery, not while deploying.</b> An {@link ActivatedJob} carries the
 * headers of the element it comes from, so no model has to be scanned - and the header is
 * honoured for process versions this application never deployed, which is exactly the
 * case an application arriving from version 1 brings along. A value which cannot be read
 * is therefore reported when a job of that element fails rather than while the
 * application boots: a model deployed years ago cannot be corrected by the boot it would
 * complain in.
 * <p>
 * <b>What outranks what.</b> The header speaks about one task, so it beats the workflow,
 * the workflow-module and the adapter level of <code>retry-backoff</code>, and it loses
 * against the task level of it. Between two statements about the same single task the one
 * an operator can change wins: changing the header means deploying the model, and on
 * Camunda 8 a changed model is a new process version.
 * <p>
 * One instance per job worker, because that is where the memory of what was already
 * reported belongs: a broken header is worth saying once, not once per job.
 */
@Slf4j
public class Camunda8RetryBackoffHeader {

  /**
   * The header VanillaBP 1 read, kept under its name so a model does not have to be
   * touched.
   */
  public static final String HEADER_NAME = "retryBackoff";

  private final String adapterId;

  private final String workflowModuleId;

  /**
   * The elements already spoken about, so a model which is wrong stays one log line
   * rather than one per failing job. Concurrent because the handlers of one worker run on
   * as many threads as the adapter has execution slots.
   */
  private final Set<String> alreadyReported = ConcurrentHashMap.newKeySet();

  /**
   * Opens the reader of one workflow module, which remembers the elements it already spoke
   * about.
   *
   * @param adapterId The adapter id this belongs to
   * @param workflowModuleId The workflow module whose models are read
   */
  public Camunda8RetryBackoffHeader(
      final String adapterId,
      final String workflowModuleId) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;

  }

  /**
   * The backoff the model asks for, if it asks for one at all.
   *
   * @param job The job whose element may carry the header
   * @param bpmnProcessId The BPMN process id, as the core knows it
   * @param configuredInstead What applies where the model says nothing usable - named in
   *          the message about an unreadable value, so the reader learns what the failing
   *          job actually got
   * @return The modelled backoff, or <code>null</code> where the element carries no
   *         header or one which is not an ISO-8601 duration
   */
  public Duration modelledIn(
      final ActivatedJob job,
      final String bpmnProcessId,
      final Duration configuredInstead) {

    final var header = job.getCustomHeaders().get(HEADER_NAME);
    if ((header == null) || header.isBlank()) {
      return null;
    }
    try {
      return Duration.parse(header.trim());
    } catch (final DateTimeParseException e) {
      reportOnce(
          bpmnProcessId,
          job.getElementId(),
          header,
          () -> log.warn(
              "Camunda8[{}]: element '{}' of BPMN process '{}' (workflow module '{}') carries the task "
                  + "header '{}' with the value '{}', which is not an ISO-8601 duration like '{}'. A failed "
                  + "job of this element is handed out again after {} instead, which is what the "
                  + "configuration resolves to. Correct the header in the model, or drop it and configure "
                  + "'{}'",
              adapterId,
              job.getElementId(),
              bpmnProcessId,
              workflowModuleId,
              HEADER_NAME,
              header,
              Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF_ISO,
              configuredInstead,
              Camunda8AdapterConfiguration
                  .propertyKey(adapterId, "retry-backoff")));
      return null;
    }

  }

  /**
   * Says which of two statements about one task applies, where the model and the task
   * level of the configuration disagree. Not a defect and therefore no warning: both were
   * written on purpose, and the reader only needs to know which one the cluster gets.
   *
   * @param job The job whose element carries the header
   * @param bpmnProcessId The BPMN process id, as the core knows it
   * @param taskDefinition The task definition, as the core knows it
   * @param modelled What the header says
   * @param configured What the task level of the configuration says
   */
  public void reportConfigurationWins(
      final ActivatedJob job,
      final String bpmnProcessId,
      final String taskDefinition,
      final Duration modelled,
      final Duration configured) {

    if (modelled.equals(configured)) {
      return;
    }
    reportOnce(
        bpmnProcessId,
        job.getElementId(),
        String.valueOf(modelled),
        () -> log.info(
            "Camunda8[{}]: element '{}' of BPMN process '{}' (workflow module '{}') models a retry backoff "
                + "of {} in its task header '{}', while '{}' configures {}. The configured value applies: "
                + "it says as much about this one task as the header does, and it can be changed without "
                + "deploying the model as a new process version",
            adapterId,
            job.getElementId(),
            bpmnProcessId,
            workflowModuleId,
            modelled,
            HEADER_NAME,
            taskPropertyKey(bpmnProcessId, taskDefinition),
            configured));

  }

  /**
   * The key of the task level of <code>retry-backoff</code>, which is the level the header
   * competes with.
   */
  private String taskPropertyKey(
      final String bpmnProcessId,
      final String taskDefinition) {

    return "vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.retry-backoff"
        .formatted(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);

  }

  /**
   * Runs the report the first time this element is seen with this header value. The value
   * is part of the memory on purpose: a new process version which corrected the header
   * deserves to be judged again.
   */
  private void reportOnce(
      final String bpmnProcessId,
      final String elementId,
      final String headerValue,
      final Runnable report) {

    if (alreadyReported.add("%s/%s=%s".formatted(bpmnProcessId, elementId, headerValue))) {
      report.run();
    }

  }

}
