package io.vanillabp.camunda8.springboot.listeners;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the test which probes user tasks. It carries nothing but its id.
 * <p>
 * Four branches of a parallel gateway notify at the same time here. What each of them learns
 * is kept in the static maps of {@link UserTaskProbeDockerWorkflowService}, not in this row.
 * There are two reasons. The second one is also why there is no <code>@Version</code>. First,
 * a user-task notification comes on a listener job with no retry left. So a transaction which
 * rolls back on a version conflict loses that notification for good. Second, the test reads
 * what the application was TOLD, and a rollback does not undo that.
 */
@Entity
@Table(name = "C8_USER_TASK_PROBE_AGGREGATE")
@Getter
@Setter
public class UserTaskProbeDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

}
