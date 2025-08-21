package io.vanillabp.camunda8.deployment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DeploymentPersistence {

    Optional<? extends Deployment> findDeployedProcess(
            long definitionKey);

    <R extends DeployedProcess> R updateMissingWorkflowModuleIdOfDeployedProcess(
            R deployedProcess,
            String workflowModuleId);

    <R extends DeployedProcess> R addDeployedProcess(
            long definitionKey,
            int version,
            int packageId,
            String workflowModuleId,
            String bpmnProcessId,
            final DeployedBpmn bpmn,
            OffsetDateTime publishedAt);

    Optional<? extends DeploymentResource> findDeploymentResource(
            int fileId);

    <R extends DeployedBpmn> R addDeployedBpmn(
            int fileId,
            String resourceName,
            byte[] resource);

    List<? extends DeployedBpmn> getBpmnNotOfPackage(
            String workflowModuleId,
            int packageId);

}
