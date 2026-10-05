package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the aggregateChanged integration test. Every
 * attribute is shared with Camunda 8 (this adapter's sync mode is FULL), so the
 * cluster holds {@link #note} as a process variable - which is what the test reads
 * back to see WHERE a push landed.
 * <p>
 * It carries a {@link Version} attribute because the multi-instance process of that test
 * runs two iterations which both append to {@link #taskIds}. An adapter runs its
 * handlers on several execution slots, so those two iterations
 * really do run at the same time, and without the version attribute the one committing
 * last would put back what it read. With it the loser of the conflict fails, the cluster
 * redelivers its job and the retry appends to what the winner wrote - which is the path
 * described in
 * <a href="https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-aggregates">two
 * writers on one aggregate</a>.
 */
@Entity
@Table(name = "C8_PUSH_AGGREGATE")
@Getter
@Setter
public class PushDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String note;

  /**
   * The parked jobs of the multi-instance activity, comma-separated.
   */
  private String taskIds;

  /**
   * See the class comment: two multi-instance iterations write this aggregate at the
   * same time, and this is what turns a lost update into a conflict the cluster retries.
   */
  @Version
  private Long version;

}
