package io.vanillabp.camunda8;

import io.vanillabp.springboot.adapter.VanillaBpProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.Map;

@ConfigurationProperties(prefix = VanillaBpProperties.PREFIX, ignoreUnknownFields = true)
public class Camunda8VanillaBpProperties {

    private Map<String, WorkflowModuleAdapterProperties> workflowModules = Map.of();

    public Map<String, WorkflowModuleAdapterProperties> getWorkflowModules() {
        return workflowModules;
    }

    public void setWorkflowModules(Map<String, WorkflowModuleAdapterProperties> workflowModules) {

        this.workflowModules = workflowModules;
        workflowModules.forEach((workflowModuleId, properties) -> {
            properties.workflowModuleId = workflowModuleId;
        });

    }

    private static final WorkflowModuleAdapterProperties defaultProperties = new WorkflowModuleAdapterProperties();
    private static final AdapterConfiguration defaultAdapterProperties = new AdapterConfiguration();

    public String getTenantId(
            final String workflowModuleId) {

        final var configuration = workflowModules
                .getOrDefault(workflowModuleId, defaultProperties)
                .getAdapters()
                .getOrDefault(Camunda8AdapterConfiguration.ADAPTER_ID, defaultAdapterProperties);
        if (!configuration.isUseTenants()) {
            return null;
        }
        if (StringUtils.hasText(configuration.getTenant())) {
            return configuration.getTenant();
        }
        return workflowModuleId;

    }

    public static class AdapterConfiguration {

        private boolean useTenants = true;

        private String tenant;

        public boolean isUseTenants() {
            return useTenants;
        }

        public void setUseTenants(boolean useTenants) {
            this.useTenants = useTenants;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

    }

    public static class WorkflowModuleAdapterProperties {

        String workflowModuleId;

        private Map<String, AdapterConfiguration> adapters = Map.of();

        public Map<String, AdapterConfiguration> getAdapters() {
            return adapters;
        }

        public void setAdapters(Map<String, AdapterConfiguration> adapters) {
            this.adapters = adapters;
        }

    }

}
