package io.vanillabp.camunda8.wiring.parameters;

import io.vanillabp.springboot.parameters.TaskParameter;

import java.util.List;

public class Camunda8TaskParameter extends TaskParameter implements ParameterVariables {

    public Camunda8TaskParameter(
            final int index,
            final String parameter,
            final String name) {
        
        super(index, parameter, name);

    }
    
    @Override
    public List<String> getVariables() {
        
        return List.of(getName());
        
    }

}
