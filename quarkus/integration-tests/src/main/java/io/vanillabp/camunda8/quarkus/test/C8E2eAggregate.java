package io.vanillabp.camunda8.quarkus.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the Quarkus end-to-end application - a JPA entity on H2,
 * because the phase-two outbox Camunda 8 needs for every operation reaching the
 * cluster is attributed to the persistence technology of the aggregate.
 * <p>
 * Its attributes travel to the cluster as process variables, which is what the FEEL
 * gateway of {@code SyncProcess} evaluates and what a pushed aggregate is read back
 * from.
 */
@Entity
@Table(name = "C8_E2E_AGGREGATE")
@Getter
@Setter
public class C8E2eAggregate {

  /**
   * An id range of its own: the aggregate's id identifies the workflow at the cluster,
   * so the id spaces of the application's aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c8E2eSeq")
  @SequenceGenerator(name = "c8E2eSeq", initialValue = 100000, allocationSize = 1)
  private Long id;

  /**
   * What the handlers record, appended in the order they ran.
   */
  @Column(length = 2000)
  private String results;

  /**
   * The job or user-task key an asynchronous task left behind.
   */
  private String taskId;

  /**
   * What the exclusive gateway of {@code SyncProcess} branches on. It is set by the
   * {@code @WorkflowTask} method right in front of that gateway, so the cluster can
   * only evaluate it if the job completion pushed it.
   */
  private boolean approved;

  /**
   * Never shared with the cluster. This single annotation also puts the class into
   * the opt-out mode "share everything else", which is what all other attributes here
   * rely on.
   */
  @NoSyncWithBPMS
  private String secret;

  /**
   * What the iterations of the flat multi-instance task were handed.
   */
  @Column(length = 2000)
  private String flat;

  /**
   * What the iterations of the task inside the multi-instance subprocess were handed.
   */
  @Column(length = 2000)
  private String nested;

  /**
   * What a plain task of a called process was told about the caller's iteration.
   */
  @Column(length = 2000)
  private String inCalledProcess;

  /**
   * What a task which is multi-instance in the called process was told about both.
   */
  @Column(length = 2000)
  private String bothChains;

  /**
   * The order the chain arrived in, as the resolver saw it.
   */
  @Column(length = 2000)
  private String chainOrder;

  /**
   * What a task two call activities away from the iteration was told.
   */
  @Column(length = 2000)
  private String twoLevelsDown;

  /**
   * Appends one result entry.
   *
   * @param result What the handler wants to record
   */
  public void appendResult(
      final String result) {

    results = results == null
        ? result
        : results
            + "|"
            + result;

  }

}
