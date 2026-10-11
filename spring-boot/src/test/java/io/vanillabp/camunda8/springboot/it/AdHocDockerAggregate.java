package io.vanillabp.camunda8.springboot.it;

import java.util.LinkedHashSet;
import java.util.Set;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the ad-hoc subprocess integration test.
 *
 * <p>
 * The element reads {@code checksToRun}. VanillaBP shares it with the cluster on every
 * command, and a collection travels as a list. The FEEL expression of the model turns that
 * list into the activities to activate.
 * </p>
 *
 * <p>
 * The activated activities run at the same time, and each of them writes this row.
 * {@code @DynamicUpdate} limits the update of a branch to the column it changed. That is
 * enough, because every check has a column of its own. The wiki page about workflow
 * aggregates shows the alternatives, and why an aggregate which two branches write needs
 * some thought. This test is about the element, not about the collision.
 * </p>
 */
@Entity
@DynamicUpdate
@Table(name = "C8_ADHOC_AGGREGATE")
@Getter
@Setter
public class AdHocDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The element ids of the activities to run, read by the model. */
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "C8_ADHOC_CHECKS", joinColumns = @JoinColumn(name = "AGGREGATE_ID"))
  @Column(name = "ELEMENT_ID")
  private Set<String> checksToRun = new LinkedHashSet<>();

  /** Written by the activity of the same name, if it was activated. */
  @Column
  private Boolean fraudChecked;

  /** Written by the activity of the same name, if it was activated. */
  @Column
  private Boolean incomeChecked;

  /** Written by the activity of the same name, if it was activated. */
  @Column
  private Boolean collateralChecked;

  /** Written by the service task AFTER the subprocess. So it says the element was left. */
  @Column
  private Boolean summarized;

}
