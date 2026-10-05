package io.vanillabp.camunda8.analysis895;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate. Every column the driver needs to know how far a workflow got is
 * here, so a restart of the application carries on where the last run stopped.
 */
@Entity
@Table(name = "A895_AGGREGATE")
@Getter
@Setter
public class ReadModelAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** What the driver did last, see {@link LoadDriver.Stage}. */
  private String stage;

  /** The open service task 'check', set by its handler. */
  private String checkTaskId;

  /** The user task 'approve', set by its CREATED notification. */
  private String approveTaskId;

  /** Pushed at the workflow's own scope. */
  private String globalMarker;

  /** Pushed into the scope the task 'check' runs in, the subprocess. */
  private String taskMarker;

  /** How the workflow ended, set by the @WorkflowEnded method. */
  private String endedAs;

  /**
   * How long the workflow waits between 'work' and the subprocess, an ISO duration. The
   * targeted run of the task-scoped push sets it, so a task can come into being while the
   * exporter stands still although its workflow was exported before.
   */
  private String delay;

  /** When the driver planned the start, for the timeline of the report. */
  private Long startedAt;

}
