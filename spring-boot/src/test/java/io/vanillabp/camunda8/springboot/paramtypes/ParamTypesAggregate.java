package io.vanillabp.camunda8.springboot.paramtypes;

import java.math.BigDecimal;
import java.math.BigInteger;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The workflow aggregate of the parameter-types integration test. Every public getter of
 * an aggregate is shared with the cluster, so the four numbers and the nested object
 * below are what the process variables of this scenario hold.
 * <p>
 * Each of the four numbers is chosen so that one of the types a handler may declare cannot
 * hold it. That is what the test around this aggregate is about.
 */
@Entity
@Table(name = "C8_PARAM_TYPES_AGGREGATE")
public class ParamTypesAggregate {

  /**
   * A decimal with a trailing zero, which is what the scale question is about.
   */
  public static final BigDecimal TOTAL = new BigDecimal("120.50");

  /**
   * A whole number above 2^53, so a detour through a double would change it.
   */
  public static final BigInteger HUGE = new BigInteger("9007199254740993");

  /**
   * A float whose double form is a number nobody typed.
   */
  public static final Float RATE = Float.valueOf(0.1f);

  /**
   * A long above Integer.MAX_VALUE.
   */
  public static final Long COUNT = Long.valueOf(3000000000L);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private BigDecimal totalValue;

  private String hugeText;

  private Float rateValue;

  private Long countValue;

  public Long getId() {

    return id;

  }

  public BigDecimal getTotal() {

    return totalValue;

  }

  /**
   * A BigInteger has no JPA column type of its own, so the aggregate keeps the text and
   * shares the number.
   */
  @Transient
  public BigInteger getHuge() {

    return new BigInteger(hugeText);

  }

  public Float getRate() {

    return rateValue;

  }

  public Long getCount() {

    return countValue;

  }

  /**
   * The same decimal one level down. No cell of this test reads it, and it travels along
   * so that the cluster holds what the measurement measured against.
   */
  @Transient
  public ParamTypesOrder getOrder() {

    return new ParamTypesOrder(totalValue);

  }

  public void fillWithTheSample() {

    totalValue = TOTAL;
    hugeText = HUGE.toString();
    rateValue = RATE;
    countValue = COUNT;

  }

}
