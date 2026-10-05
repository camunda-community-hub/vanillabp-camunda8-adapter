package io.vanillabp.camunda8.quarkus.test;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the aggregateChanged tests. Every attribute is
 * shared with the cluster, so {@link #note} arrives there as a process variable - and
 * reading it back with its scope is how the test sees WHERE a push landed.
 * <p>
 * It carries a {@link Version} attribute because the multi-instance process runs two
 * iterations at once on the adapter's execution slots: without it the one committing
 * last would put back what it read, with it the loser of the conflict fails and the
 * cluster redelivers its job.
 */
@Entity
@Table(name = "C8_E2E_PUSH_AGGREGATE")
@Getter
@Setter
public class C8PushAggregate {

  /**
   * An id range of its own, see {@link C8E2eAggregate}.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c8PushSeq")
  @SequenceGenerator(name = "c8PushSeq", initialValue = 500000, allocationSize = 1)
  private Long id;

  private String note;

  /**
   * The parked jobs of the multi-instance activity, comma-separated.
   */
  private String taskIds;

  /**
   * See the class comment.
   */
  @Version
  private Long version;

}
