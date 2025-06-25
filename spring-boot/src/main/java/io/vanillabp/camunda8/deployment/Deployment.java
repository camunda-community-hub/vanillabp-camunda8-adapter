package io.vanillabp.camunda8.deployment;

import java.time.OffsetDateTime;

public interface Deployment {

    long getDefinitionKey();

    int getVersion();

    String getWorkflowModuleId();

    int getPackageId();

    OffsetDateTime getPublishedAt();

    <R extends DeploymentResource> R getDeployedResource();

}
