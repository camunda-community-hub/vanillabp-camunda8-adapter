package org.camunda.community.vanillabp.c8.wiring.parameters;

import io.vanillabp.springboot.parameters.TaskParameter;

import java.util.List;

public class Camunda8TaskParameter extends TaskParameter implements ParameterVariables {

    public Camunda8TaskParameter(
            final String name) {
        
        super(name);

    }
    
    @Override
    public List<String> getVariables() {
        
        return List.of(getName());
        
    }

}
