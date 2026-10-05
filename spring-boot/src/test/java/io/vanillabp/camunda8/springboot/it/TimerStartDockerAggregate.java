package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the timer-start integration test. Nobody starts this workflow
 * through the {@code ProcessService}, so the <code>@WorkflowStartedByBpms</code> method is
 * the one moment at which it gets a name.
 */
@Entity
@Table(name = "C8_TIMER_START_AGGREGATE")
@Getter
@Setter
public class TimerStartDockerAggregate {

  @Id
  private String id;

  private String processedBy;

  /**
   * Which kind of start event began this workflow, as the trigger reported it.
   */
  private String startedBy;

  /**
   * Set by the <code>@WorkflowEnded</code> method.
   */
  private String endedAs;

}
