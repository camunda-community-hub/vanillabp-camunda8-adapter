package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the decomposition test. One aggregate for the caller, the
 * called process and the process that one calls, which is what
 * <code>secondaryBpmnProcesses</code> says: the three models are one business case.
 */
@Entity
@Table(name = "C8_MI_CALL_AGGREGATE")
@Getter
@Setter
public class MiCallDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** What a plain task of the called process was told about the caller's iteration. */
  @Column(length = 2000)
  private String inCalledProcess;

  /** What a task which is multi-instance in the called process was told about both. */
  @Column(length = 2000)
  private String bothChains;

  /** The order the chain arrived in, as the resolver saw it. */
  @Column(length = 2000)
  private String chainOrder;

  /** What a task two call activities away from the iteration was told. */
  @Column(length = 2000)
  private String twoLevelsDown;

}
