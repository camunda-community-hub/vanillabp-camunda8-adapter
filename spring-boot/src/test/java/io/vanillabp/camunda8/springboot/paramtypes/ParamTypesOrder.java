package io.vanillabp.camunda8.springboot.paramtypes;

import java.math.BigDecimal;

/**
 * The nested value of the parameter-types integration test. Its members travel inside one
 * object variable. So a handler reads what the cluster's JSON says, not what the aggregate's
 * own types say.
 */
public class ParamTypesOrder {

  private final BigDecimal total;

  ParamTypesOrder(
      final BigDecimal total) {

    this.total = total;

  }

  public BigDecimal getTotal() {

    return total;

  }

}
