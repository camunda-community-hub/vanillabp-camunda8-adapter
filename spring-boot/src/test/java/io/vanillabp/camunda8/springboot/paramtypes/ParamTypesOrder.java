package io.vanillabp.camunda8.springboot.paramtypes;

import java.math.BigDecimal;

/**
 * The nested value of the parameter-types integration test. Its members travel inside one
 * object variable, so what a handler reads out of them is what the cluster's JSON says
 * rather than what the aggregate's own types say.
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
