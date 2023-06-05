package io.vanillabp.camunda8.wiring.parameters;

import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.springboot.parameters.ResolverBasedMultiInstanceMethodParameter;

import java.util.List;
import java.util.stream.Collectors;

public class Camunda8ResolverBasedMethodParameter extends ResolverBasedMultiInstanceMethodParameter
        implements ParameterVariables {

    public Camunda8ResolverBasedMethodParameter(
            final String parameter,
            final MultiInstanceElementResolver<?, ?> resolverBean) {

        super(parameter, resolverBean);

    }
    
    @Override
    public List<String> getVariables() {
        
        return resolverBean
                .getNames()
                .stream()
                .flatMap(name -> List.of(
                        name,
                        name + Camunda8MultiInstanceTotalMethodParameter.SUFFIX,
                        name + Camunda8MultiInstanceIndexMethodParameter.SUFFIX).stream())
                .collect(Collectors.toList());

    }

}
