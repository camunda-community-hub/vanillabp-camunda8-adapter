package io.vanillabp.camunda8.wiring.parameters;

import io.vanillabp.springboot.parameters.MultiInstanceElementMethodParameter;

import java.util.List;

public class Camunda8MultiInstanceElementMethodParameter extends MultiInstanceElementMethodParameter
        implements ParameterVariables {

    public Camunda8MultiInstanceElementMethodParameter(
            final int index,
            final String parameter,
            final String name) {
        
        super(index, parameter, name);
        
    }

    @Override
    public List<String> getVariables() {

        return List.of(name);

    }

}
