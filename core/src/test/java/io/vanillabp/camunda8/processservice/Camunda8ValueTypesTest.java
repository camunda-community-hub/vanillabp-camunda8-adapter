package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter tells the startup check about a value type. The README says the same
 * three things in prose, and these are the tests which keep it true.
 */
@ExtendWith(SuppressOutputExtension.class)
@DisplayName("What Camunda 8 does with a value type")
public class Camunda8ValueTypesTest {

  @Test
  @DisplayName("a text, a boolean and a number a JSON value carries survive")
  public void theTypesJsonCarriesSurvive() {

    for (final var type : new Class<?>[]{
        String.class, Boolean.class, boolean.class, Integer.class, long.class, Double.class
    }) {
      assertEquals(
          ValueTypeVerdict.Kind.SURVIVES,
          Camunda8ValueTypes.verdictFor(type, ValueDirection.TO_BPMS).kind(),
          type.getName());
    }

  }

  @Test
  @DisplayName("an enum survives, because VanillaBP hands it over as its name")
  public void anEnumSurvives() {

    assertEquals(
        ValueTypeVerdict.Kind.SURVIVES,
        Camunda8ValueTypes.verdictFor(ValueDirection.class, ValueDirection.TO_BPMS).kind());

  }

  @Test
  @DisplayName("a decimal is changed, because the broker holds a number and not its scale")
  public void aDecimalIsChangedInBothDirections() {

    final var outbound = Camunda8ValueTypes.verdictFor(BigDecimal.class, ValueDirection.TO_BPMS);
    final var inbound = Camunda8ValueTypes.verdictFor(BigDecimal.class, ValueDirection.FROM_BPMS);

    assertEquals(ValueTypeVerdict.Kind.CHANGED, outbound.kind());
    assertEquals(ValueTypeVerdict.Kind.CHANGED, inbound.kind());
    assertNotNull(outbound.explanation());
    assertNotNull(inbound.explanation());

  }

  @Test
  @DisplayName("anything else is answered with cannot say rather than with a guess")
  public void anythingElseIsUnknown() {

    final var verdict = Camunda8ValueTypes.verdictFor(UUID.class, ValueDirection.FROM_BPMS);

    assertEquals(ValueTypeVerdict.Kind.CANNOT_SAY, verdict.kind());
    assertNotNull(verdict.explanation());

  }

}
