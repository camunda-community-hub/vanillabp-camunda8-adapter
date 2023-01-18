package org.camunda.community.vanillabp.c8.wiring.parameters;

import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.springboot.parameters.MethodParameterFactory;
import io.vanillabp.springboot.parameters.MultiInstanceElementMethodParameter;
import io.vanillabp.springboot.parameters.MultiInstanceIndexMethodParameter;
import io.vanillabp.springboot.parameters.MultiInstanceTotalMethodParameter;
import io.vanillabp.springboot.parameters.ResolverBasedMultiInstanceMethodParameter;
import io.vanillabp.springboot.parameters.TaskParameter;

public class Camunda8MethodParameterFactory extends MethodParameterFactory {

    @Override
    public ResolverBasedMultiInstanceMethodParameter getResolverBasedMultiInstanceMethodParameter(
            final MultiInstanceElementResolver<?, ?> resolverBean) {

        return new Camunda8ResolverBasedMethodParameter(resolverBean);

    }

    @Override
    public MultiInstanceElementMethodParameter getMultiInstanceElementMethodParameter(
            final String name) {

        return new Camunda8MultiInstanceElementMethodParameter(name);

    }

    @Override
    public MultiInstanceIndexMethodParameter getMultiInstanceIndexMethodParameter(
            final String name) {

        return new Camunda8MultiInstanceIndexMethodParameter(name);

    }

    @Override
    public MultiInstanceTotalMethodParameter getMultiInstanceTotalMethodParameter(
            final String name) {

        return new Camunda8MultiInstanceTotalMethodParameter(name);

    }
    
    @Override
    public TaskParameter getTaskParameter(
            final String name) {
        
        return new Camunda8TaskParameter(name);
        
    }
    
}
