package org.camunda.community.vanillabp.c8.wiring.parameters;

import io.vanillabp.springboot.parameters.MultiInstanceIndexMethodParameter;

import java.util.List;

public class Camunda8MultiInstanceIndexMethodParameter extends MultiInstanceIndexMethodParameter
        implements ParameterVariables {

    public static final String SUFFIX = "_index";

    public Camunda8MultiInstanceIndexMethodParameter(
            final String name) {

        super(name);

    }

    @Override
    public List<String> getVariables() {
        
        return List.of(name + SUFFIX);
        
    }

}
