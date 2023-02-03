package io.vanillabp.camunda8.wiring.parameters;

import io.vanillabp.springboot.parameters.MultiInstanceTotalMethodParameter;

import java.util.List;

public class Camunda8MultiInstanceTotalMethodParameter extends MultiInstanceTotalMethodParameter
        implements ParameterVariables {

    public static final String SUFFIX = "_total";

    public Camunda8MultiInstanceTotalMethodParameter(
            final String name) {
        
        super(name);
        
    }

    @Override
    public List<String> getVariables() {

        return List.of(name + SUFFIX);

    }

}
