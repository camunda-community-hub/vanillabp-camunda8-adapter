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
 * Four branches of a parallel gateway notify at once here, and what each of them learns is
 * kept in the static maps of {@link UserTaskProbeDockerWorkflowService} rather than in this
 * row. Two reasons, and the second one is the reason there is no <code>@Version</code>
 * either. A user-task notification rides on a listener job with no retry left, so a
 * transaction which rolls back on a version conflict loses that notification for good. And
 * what the test reads is what the application was TOLD, which a rollback does not undo.
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
