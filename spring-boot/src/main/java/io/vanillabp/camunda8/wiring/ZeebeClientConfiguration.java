package io.vanillabp.camunda8.wiring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.camunda.zeebe.client.api.JsonMapper;
import io.camunda.zeebe.client.impl.ZeebeObjectMapper;

@Configuration
public class ZeebeClientConfiguration {

    @Bean
    public JsonMapper zeebeJsonMapper(final ObjectMapper mapper) {

        return new ZeebeObjectMapper(mapper);

    }

}
