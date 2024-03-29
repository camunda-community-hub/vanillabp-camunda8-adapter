package io.vanillabp.camunda8.wiring.parameters;

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
            final int index,
            final String parameter,
            final MultiInstanceElementResolver<?, ?> resolverBean) {

        return new Camunda8ResolverBasedMethodParameter(
                index,
                parameter,
                resolverBean);

    }

    @Override
    public MultiInstanceElementMethodParameter getMultiInstanceElementMethodParameter(
            final int index,
            final String parameter,
            final String name) {

        return new Camunda8MultiInstanceElementMethodParameter(
                index,
                parameter,
                name);

    }

    @Override
    public MultiInstanceIndexMethodParameter getMultiInstanceIndexMethodParameter(
            final int index,
            final String parameter,
            final String name) {

        return new Camunda8MultiInstanceIndexMethodParameter(
                index,
                parameter,
                name);

    }

    @Override
    public MultiInstanceTotalMethodParameter getMultiInstanceTotalMethodParameter(
            final int index,
            final String parameter,
            final String name) {

        return new Camunda8MultiInstanceTotalMethodParameter(
                index,
                parameter,
                name);

    }
    
    @Override
    public TaskParameter getTaskParameter(
            final int index,
            final String parameter,
            final String name) {
        
        return new Camunda8TaskParameter(
                index,
                parameter,
                name);
        
    }
    
}
