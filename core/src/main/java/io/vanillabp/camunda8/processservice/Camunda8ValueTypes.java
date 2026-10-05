package io.vanillabp.camunda8.processservice;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;

import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;

/**
 * What Camunda 8 does with one Java type, answered for the startup check which asks
 * whether a value survives the way to the cluster and back.
 * <p>
 * A Camunda 8 variable is a JSON value. The broker knows a text, a boolean, a number, a
 * list and an object, and nothing else, so a Java type reaches the cluster as whichever of
 * those it was mapped to and comes back as the Java type that JSON value is read into.
 * <p>
 * The costly case is the decimal. The broker holds a number and not the way it was
 * written, so <code>120.50</code> comes back as <code>120.5</code> (measured while story
 * 241 was implemented). Nothing short of a round trip through a running cluster shows
 * that, which is why a type this class cannot place is answered with "cannot say" rather
 * than with a guess: an application whose cluster is unreachable while it boots still has
 * to boot.
 */
public final class Camunda8ValueTypes {

  /**
   * The Java types a JSON value carries there and back: the texts, the boolean and the
   * numbers the broker stores as numbers.
   */
  private static final Set<Class<?>> JSON_CARRIES_IT = Set
      .of(
          String.class,
          Boolean.class,
          boolean.class,
          Integer.class,
          int.class,
          Long.class,
          long.class,
          Short.class,
          short.class,
          Byte.class,
          byte.class,
          Double.class,
          double.class,
          Float.class,
          float.class);

  private Camunda8ValueTypes() {
  }

  /**
   * What the cluster does with that type.
   *
   * @param valueType The declared type of the value
   * @param direction Which way the value travels
   * @return The verdict for the startup check
   */
  public static ValueTypeVerdict verdictFor(
      final Class<?> valueType,
      final ValueDirection direction) {

    if (valueType == null) {
      return ValueTypeVerdict.cannotSay("no type was named");
    }
    if (valueType.isEnum()) {
      // VanillaBP hands an enum over as its name, and a text is a JSON value
      return ValueTypeVerdict.survives();
    }
    if (JSON_CARRIES_IT.contains(valueType) || CharSequence.class.isAssignableFrom(valueType)) {
      return ValueTypeVerdict.survives();
    }
    if ((valueType == BigDecimal.class) || (valueType == BigInteger.class)) {
      return direction == ValueDirection.TO_BPMS
          ? ValueTypeVerdict
              .changed(
                  """
                      that the broker holds a JSON number and not the way it was written: \
                      120.50 is stored as 120.5, so a FEEL expression and anything reading the \
                      variable sees the shorter form""")
          : ValueTypeVerdict
              .changed(
                  """
                      that the number arrives as the declared type but without the scale it was \
                      written with, because the broker never held that scale""");
    }
    return ValueTypeVerdict
        .cannotSay(
            """
                that a %s is no JSON value of its own and reaches the broker as whatever the \
                client serialized it to"""
                .formatted(valueType.getSimpleName()));

  }

}
