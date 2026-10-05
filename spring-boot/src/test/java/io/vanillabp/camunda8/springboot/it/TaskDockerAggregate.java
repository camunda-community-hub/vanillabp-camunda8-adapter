package io.vanillabp.camunda8.springboot.it;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the task-processing integration test.
 */
@Entity
@Table(name = "C8_TASK_AGGREGATE")
@Getter
@Setter
public class TaskDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String results;

  private String taskId;

  /**
   * The attribute a FEEL gateway condition of {@code SyncProcess} branches on - it
   * is set by the {@code @WorkflowTask} method right before that gateway, so the
   * cluster can only evaluate it if the job completion PUSHED it.
   */
  private boolean approved;

  /**
   * Never shared with the cluster. This one annotation also derives
   * the CLASS mode "share everything else" (opt-out) - which is what this aggregate
   * relies on.
   */
  @NoSyncWithBPMS
  private String secret;

  public void appendResult(
      final String result) {

    results = results == null
        ? result
        : results
            + "|"
            + result;

  }

}
