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
 * JPA workflow aggregate of the test about a process called by an expression. The caller and
 * the called process share it, which is what makes the call decomposition rather than the
 * start of a business case of its own.
 */
@Entity
@Table(name = "C8_MI_FEEL_AGGREGATE")
@Getter
@Setter
public class MiFeelDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * The process the call activities name, read by the expression
   * <code>=whichProcess</code>. It travels to the cluster as a start variable, so the model
   * says which process it calls without the deployment being able to say it.
   */
  @Column(length = 200)
  private String whichProcess;

  /** What the task of the called process was told, one line per call. */
  @Column(length = 2000)
  private String reported;

}
