package io.vanillabp.camunda8;

import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.vanillabp.springboot.adapter.VanillaBpProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = VanillaBpProperties.PREFIX, ignoreUnknownFields = true)
public class Camunda8VanillaBpProperties {

    private boolean allowConnectors = false;

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
    private static final WorkflowModuleAdapterConfiguration defaultAdapterProperties = new WorkflowModuleAdapterConfiguration();

    public String getTenantId(
            final String workflowModuleId) {

        final var configuration = workflowModules
                .getOrDefault(workflowModuleId, defaultProperties)
                .getAdapters()
                .getOrDefault(Camunda8AdapterConfiguration.ADAPTER_ID, defaultAdapterProperties);
        if (!configuration.isUseTenants()) {
            return null;
        }
        if (StringUtils.hasText(configuration.getTenantId())) {
            return configuration.getTenantId();
        }
        return workflowModuleId;

    }

    public boolean isTaskIdAsHexString(
            final String workflowModuleId) {

        final var configuration = workflowModules
                .getOrDefault(workflowModuleId, defaultProperties)
                .getAdapters()
                .getOrDefault(Camunda8AdapterConfiguration.ADAPTER_ID, defaultAdapterProperties);
        return configuration.isTaskIdAsHexString();

    }

    public WorkerProperties getUserTaskWorkerProperties(
            final String workflowModuleId) {

        return getWorkerProperties(workflowModuleId, null, null);

    }

    public WorkerProperties getWorkerProperties(
            final String workflowModuleId,
            final String bpmnProcessId,
            final String taskDefinition) {

        WorkerProperties result = new WorkerProperties();

        final var workflowModule = workflowModules.get(workflowModuleId);
        if (workflowModule == null) {
            return result;
        }
        final var workflowModuleAdapter = workflowModule.getAdapters().get(Camunda8AdapterConfiguration.ADAPTER_ID);
        if (workflowModuleAdapter != null) {
            result.apply(workflowModuleAdapter);
        }

        if (bpmnProcessId == null) {
            return result;
        }
        final var workflow = workflowModule.getWorkflows().get(bpmnProcessId);
        if (workflow == null) {
            return result;
        }
        final var workflowAdapter = workflow.getAdapters().get(Camunda8AdapterConfiguration.ADAPTER_ID);
        if (workflowAdapter != null) {
            result.apply(workflowAdapter);
        }

        if (taskDefinition == null) {
            return result;
        }
        final var task = workflow.getTasks().get(taskDefinition);
        if (task == null) {
            return result;
        }
        final var taskAdapter = task.getAdapters().get(Camunda8AdapterConfiguration.ADAPTER_ID);
        if (taskAdapter != null) {
            result.apply(taskAdapter);
        }

        return result;

    }

    public boolean areConnectorsAllowed(
            final String workflowModuleId,
            final String bpmnProcessId) {

        boolean result = allowConnectors;

        final var workflowModule = workflowModules.get(workflowModuleId);
        if (workflowModule == null) {
            return result;
        }
        final var workflowModuleAllowsConnectors = workflowModule.isAllowConnectors();
        if (workflowModuleAllowsConnectors) {
            result = workflowModuleAllowsConnectors;
        }

        if (bpmnProcessId == null) {
            return result;
        }
        final var workflow = workflowModule.getWorkflows().get(bpmnProcessId);
        if (workflow == null) {
            return result;
        }
        final var workflowAllowConnectors = workflow.isAllowConnectors();
        if (workflowAllowConnectors) {
            result = workflowAllowConnectors;
        }

        return result;

    }

    public void setAllowConnectors(boolean allowConnectors) {
        this.allowConnectors = allowConnectors;
    }

    public static class WorkflowModuleAdapterConfiguration extends AdapterConfiguration {

        private boolean taskIdAsHexString = false;

        public boolean isTaskIdAsHexString() {
            return taskIdAsHexString;
        }

        public void setTaskIdAsHexString(boolean taskIdAsHexString) {
            this.taskIdAsHexString = taskIdAsHexString;
        }

    }

    public static class AdapterConfiguration extends WorkerProperties {

        private boolean useTenants = true;

        private String tenantId;

        public boolean isUseTenants() {
            return useTenants;
        }

        public void setUseTenants(boolean useTenants) {
            this.useTenants = useTenants;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

    }

    public static class WorkflowModuleAdapterProperties {

        String workflowModuleId;

        boolean allowConnectors;

        private Map<String, WorkflowModuleAdapterConfiguration> adapters = Map.of();

        private Map<String, WorkflowAdapterProperties> workflows = Map.of();

        public Map<String, WorkflowModuleAdapterConfiguration> getAdapters() {
            return adapters;
        }

        public void setAdapters(Map<String, WorkflowModuleAdapterConfiguration> adapters) {
            this.adapters = adapters;
        }

        public Map<String, WorkflowAdapterProperties> getWorkflows() { return workflows; }

        public void setWorkflows(Map<String, WorkflowAdapterProperties> workflows) {

            this.workflows = workflows;
            workflows.forEach((bpmnProcessId, properties) -> {
                properties.bpmnProcessId = bpmnProcessId;
                properties.workflowModule = this;
            });

        }

        public boolean isAllowConnectors() {
            return allowConnectors;
        }

        public void setAllowConnectors(boolean allowConnectors) {
            this.allowConnectors = allowConnectors;
        }
    }

    public static class WorkflowAdapterProperties {

        String bpmnProcessId;

        WorkflowModuleAdapterProperties workflowModule;

        boolean allowConnectors;

        private Map<String, WorkerProperties> adapters = Map.of();

        private Map<String, TaskProperties> tasks = Map.of();

        public WorkflowModuleAdapterProperties getWorkflowModule() {
            return workflowModule;
        }

        public String getBpmnProcessId() {
            return bpmnProcessId;
        }

        public Map<String, WorkerProperties> getAdapters() {
            return adapters;
        }

        public void setAdapters(Map<String, WorkerProperties> adapters) {
            this.adapters = adapters;
        }

        public Map<String, TaskProperties> getTasks() {
            return tasks;
        }

        public void setTasks(Map<String, TaskProperties> tasks) {
            this.tasks = tasks;
        }

        public boolean isAllowConnectors() {
            return allowConnectors;
        }

        public void setAllowConnectors(boolean allowConnectors) {
            this.allowConnectors = allowConnectors;
        }
    }

    public static class WorkerProperties {

        public WorkerProperties() {}

        public void apply(
                final WorkerProperties original) {

            if (original.taskTimeout != null) {
                this.taskTimeout = original.taskTimeout;
            }
            if (original.pollInterval != null) {
                this.pollInterval = original.pollInterval;
            }
            if (original.pollRequestTimeout != null) {
                this.pollRequestTimeout = original.pollRequestTimeout;
            }
            if (original.isStreamEnabled() != null) {
                this.streamEnabled = original.isStreamEnabled();
            }
            if (original.streamTimeout != null) {
                this.streamTimeout = original.streamTimeout;
            }

        }

        public void applyToWorker(
                final JobWorkerBuilderStep1.JobWorkerBuilderStep3 workerBuilder) {

            if (taskTimeout != null) {
                workerBuilder.timeout(taskTimeout);
            }
            applyToUserTaskWorker(workerBuilder);

        }

        public void applyToUserTaskWorker(
                final JobWorkerBuilderStep1.JobWorkerBuilderStep3 workerBuilder) {

            if (pollInterval != null) {
                workerBuilder.pollInterval(pollInterval);
            }
            if (pollRequestTimeout != null) {
                workerBuilder.requestTimeout(pollRequestTimeout);
            }
            if (streamEnabled!= null) {
                workerBuilder.streamEnabled(streamEnabled);
            }
            if (streamTimeout != null) {
                workerBuilder.streamTimeout(streamTimeout);
            }

        }

        private Duration taskTimeout = null;

        private Duration pollInterval = null;

        private Duration pollRequestTimeout = null;

        private Boolean streamEnabled = null;

        private Duration streamTimeout = null;

        public Duration getTaskTimeout() {
            return taskTimeout;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public Duration getPollRequestTimeout() {
            return pollRequestTimeout;
        }

        public Boolean isStreamEnabled() {
            return streamEnabled;
        }

        public Duration getStreamTimeout() {
            return streamTimeout;
        }

        public void setTaskTimeout(Duration taskTimeout) {
            this.taskTimeout = taskTimeout;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public void setPollRequestTimeout(Duration pollRequestTimeout) {
            this.pollRequestTimeout = pollRequestTimeout;
        }

        public void setStreamEnabled(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
        }

        public void setStreamTimeout(Duration streamTimeout) {
            this.streamTimeout = streamTimeout;
        }

    }

    public static class TaskProperties {

        private Map<String, WorkerProperties> adapters = Map.of();

        public Map<String, WorkerProperties> getAdapters() {
            return adapters;
        }

        public void setAdapters(Map<String, WorkerProperties> adapters) {
            this.adapters = adapters;
        }

    }

}
