package io.vanillabp.camunda8.config;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/**
 * Disable all of Camunda autoconfiguration regarding {@link io.camunda.spring.client.annotation.JobWorker}
 * and {@link io.camunda.spring.client.annotation.Deployment} processing.
 */
public class DisableCamundaSpringAutoConfigurationImportFilter implements AutoConfigurationImportFilter {

    private static final List<String> SHOULD_SKIP = List.of(
            "io.camunda.spring.client.configuration.CamundaAutoConfiguration");

    @Override
    public boolean[] match(String[] classNames, AutoConfigurationMetadata metadata) {
        boolean[] matches = new boolean[classNames.length];

        for (int i = 0; i < classNames.length; i++) {
            matches[i] = classNames[i] == null || !SHOULD_SKIP.contains(classNames[i]);
        }
        return matches;
    }

}
