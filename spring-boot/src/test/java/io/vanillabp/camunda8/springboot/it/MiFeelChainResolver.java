package io.vanillabp.camunda8.springboot.it;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.vanillabp.spi.service.MultiInstanceElementResolver;

/**
 * Writes down every iteration the task was told about, in the order it arrived. A resolver
 * rather than one parameter per level, because a case which loses the chain reports nothing
 * at all and a named parameter could not say so.
 */
@Component
public class MiFeelChainResolver implements MultiInstanceElementResolver<MiFeelDockerAggregate, String> {

  /** What this resolver reads, which all belongs to the calling process. */
  @Override
  public Collection<String> getNames() {

    return List.of("MIF_Clean", "MIF_CallClean", "MIF_List");

  }

  @Override
  public String resolve(
      final MiFeelDockerAggregate workflowAggregate,
      final Map<String, MultiInstance<Object>> multiInstances) {

    if (multiInstances.isEmpty()) {
      return "nothing";
    }
    return multiInstances
        .entrySet()
        .stream()
        .map(
            level -> "%s:%s#%d/%d"
                .formatted(
                    level.getKey(),
                    level.getValue().getElement(),
                    level.getValue().getIndex(),
                    level.getValue().getTotal()))
        .collect(Collectors.joining(">"));

  }

}
