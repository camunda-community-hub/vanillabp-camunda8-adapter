package org.camunda.community.vanillabp.c8.wiring;

import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.camunda.zeebe.client.api.JsonMapper;
import io.camunda.zeebe.client.impl.ZeebeObjectMapper;

@Configuration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class ZeebeClientConfiguration {

    @Bean
    public JsonMapper zeebeJsonMapper(final ObjectMapper mapper) {

        return new ZeebeObjectMapper(mapper);

    }

}
