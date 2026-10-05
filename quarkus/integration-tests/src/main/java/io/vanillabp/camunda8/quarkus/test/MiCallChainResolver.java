package io.vanillabp.camunda8.quarkus.test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.vanillabp.spi.service.MultiInstanceElementResolver;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Writes down the chain in the order it arrives, which is the one thing a
 * <code>&#64;MultiInstanceElement("...")</code> per level cannot show: naming the levels
 * yourself says nothing about the order they came in. The SPI promises outermost first,
 * and across a call activity the outermost level belongs to another process.
 */
@ApplicationScoped
public class MiCallChainResolver implements MultiInstanceElementResolver<C8E2eAggregate, String> {

  @Override
  public Collection<String> getNames() {

    return List.of("MIC_PerGroup", "MIC_ChildMiTask");

  }

  @Override
  public String resolve(
      final C8E2eAggregate workflowAggregate,
      final Map<String, MultiInstance<Object>> multiInstances) {

    return String.join(">", multiInstances.keySet());

  }

}
