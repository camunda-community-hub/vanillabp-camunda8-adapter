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
 * The workflow aggregate of the parameter-types integration test. Every public getter of an
 * aggregate is shared with the cluster. So the process variables of this scenario hold the
 * four numbers and the nested object below.
 * <p>
 * Each of the four numbers is chosen so that one of the types a handler may declare cannot
 * hold it. The test around this aggregate is about that.
 */
@Entity
@Table(name = "C8_PARAM_TYPES_AGGREGATE")
public class ParamTypesAggregate {

  /**
   * A decimal with a trailing zero. The question about the scale is about this zero.
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
   * A BigInteger has no JPA column type of its own. So the aggregate stores the text and
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
   * The same decimal one level down. No branch of this test reads it. It is shared so that the
   * cluster holds the same values the measurement was taken against.
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
