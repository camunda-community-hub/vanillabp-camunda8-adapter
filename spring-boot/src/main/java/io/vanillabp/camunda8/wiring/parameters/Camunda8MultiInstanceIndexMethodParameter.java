package io.vanillabp.camunda8.wiring.parameters;

import io.vanillabp.springboot.parameters.MultiInstanceIndexMethodParameter;

import java.util.List;

public class Camunda8MultiInstanceIndexMethodParameter extends MultiInstanceIndexMethodParameter
        implements ParameterVariables {

    public static final String SUFFIX = "_index";

    public Camunda8MultiInstanceIndexMethodParameter(
            final String parameter,
            final String name) {

        super(parameter, name);

    }

    @Override
    public List<String> getVariables() {
        
        return List.of(name + SUFFIX);
        
    }

}
