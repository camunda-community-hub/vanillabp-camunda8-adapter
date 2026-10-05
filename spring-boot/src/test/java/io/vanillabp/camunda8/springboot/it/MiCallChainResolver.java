package io.vanillabp.camunda8.springboot.it;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.vanillabp.spi.service.MultiInstanceElementResolver;

/**
 * Writes down the chain in the order it arrives, which is the one thing a
 * <code>@MultiInstanceElement("...")</code> per level cannot show: naming the levels
 * yourself says nothing about the order they came in. The SPI promises outermost first,
 * and across a call activity the outermost level belongs to another process.
 */
@Component
public class MiCallChainResolver implements MultiInstanceElementResolver<MiCallDockerAggregate, String> {

  @Override
  public Collection<String> getNames() {

    return List.of("MIC_PerGroup", "MIC_ChildMiTask");

  }

  @Override
  public String resolve(
      final MiCallDockerAggregate workflowAggregate,
      final Map<String, MultiInstance<Object>> multiInstances) {

    return String.join(">", multiInstances.keySet());

  }

}
