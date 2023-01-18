package org.camunda.community.vanillabp.c8.wiring.parameters;

import io.vanillabp.springboot.parameters.MultiInstanceElementMethodParameter;

import java.util.List;

public class Camunda8MultiInstanceElementMethodParameter extends MultiInstanceElementMethodParameter
        implements ParameterVariables {

    public Camunda8MultiInstanceElementMethodParameter(
            final String name) {
        
        super(name);
        
    }

    @Override
    public List<String> getVariables() {

        return List.of(name);

    }

}
