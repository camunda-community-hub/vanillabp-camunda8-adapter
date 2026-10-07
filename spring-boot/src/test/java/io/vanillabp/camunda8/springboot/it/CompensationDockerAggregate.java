package io.vanillabp.camunda8.springboot.it;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the compensation integration test.
 *
 * <p>
 * The two handlers may write this row at the same time. {@code @DynamicUpdate} keeps each of
 * them to the column it changed, so neither write hides the other one. That is enough here
 * because every handler has a column of its own; the test is about how the cluster hands out
 * the handlers, not about two writers on one aggregate.
 * </p>
 */
@Entity
@DynamicUpdate
@Table(name = "C8_COMPENSATION_AGGREGATE")
@Getter
@Setter
public class CompensationDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Makes the first attempt of the refund throw, so the other handler can be watched. */
  @Column
  private Boolean refundFailsOnce;

  /** Written by the task of the same name. */
  @Column
  private Boolean hotelBooked;

  /** Written by the task of the same name. */
  @Column
  private Boolean paymentCharged;

  /** Written by the compensation handler of the hotel booking. */
  @Column
  private Boolean bookingCancelled;

  /** Written by the compensation handler of the payment. */
  @Column
  private Boolean paymentRefunded;

  /** Written by the task behind the throw event, so it says both handlers are done. */
  @Column
  private Boolean reported;

}
