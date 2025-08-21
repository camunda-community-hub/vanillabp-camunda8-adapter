package io.vanillabp.camunda8.deployment.jpa;

import io.vanillabp.camunda8.deployment.DeployedBpmn;
import io.vanillabp.camunda8.deployment.DeployedProcess;
import io.vanillabp.camunda8.deployment.Deployment;
import io.vanillabp.camunda8.deployment.DeploymentPersistence;
import io.vanillabp.camunda8.deployment.DeploymentResource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class JpaDeploymentPersistence implements DeploymentPersistence {

    private final DeploymentResourceRepository deploymentResourceRepository;

    private final DeploymentRepository deploymentRepository;

    private final DeployedBpmnRepository deployedBpmnRepository;

    public JpaDeploymentPersistence(
            final DeploymentResourceRepository deploymentResourceRepository,
            final DeploymentRepository deploymentRepository,
            final DeployedBpmnRepository deployedBpmnRepository) {

        this.deploymentResourceRepository = deploymentResourceRepository;
        this.deploymentRepository = deploymentRepository;
        this.deployedBpmnRepository = deployedBpmnRepository;

    }

    @Override
    public Optional<? extends Deployment> findDeployedProcess(
            final long definitionKey) {

        return deploymentRepository.findByDefinitionKey(definitionKey);

    }

    @Override
    @SuppressWarnings("unchecked")
    public <R extends DeployedProcess> R updateMissingWorkflowModuleIdOfDeployedProcess(
            final R deployedProcess,
            final String workflowModuleId) {

        final var jpaDeployedProcess = (io.vanillabp.camunda8.deployment.jpa.DeployedProcess) deployedProcess;
        jpaDeployedProcess.setWorkflowModuleId(workflowModuleId);
        return (R) deploymentRepository.save(jpaDeployedProcess);

    }

    @Override
    @SuppressWarnings("unchecked")
    public <R extends DeployedProcess> R addDeployedProcess(
            final long definitionKey,
            final int version,
            final int packageId,
            final String workflowModuleId,
            final String bpmnProcessId,
            final DeployedBpmn bpmn,
            final OffsetDateTime publishedAt) {

        final var deployedProcess = new io.vanillabp.camunda8.deployment.jpa.DeployedProcess();

        final var bpmnEntity = bpmn instanceof io.vanillabp.camunda8.deployment.jpa.DeployedBpmn
                ? (io.vanillabp.camunda8.deployment.jpa.DeployedBpmn) bpmn
                : deploymentResourceRepository.findById(bpmn.getFileId()).orElse(null);

        deployedProcess.setDefinitionKey(definitionKey);
        deployedProcess.setVersion(version);
        deployedProcess.setPackageId(packageId);
        deployedProcess.setWorkflowModuleId(workflowModuleId);
        deployedProcess.setBpmnProcessId(bpmnProcessId);
        deployedProcess.setDeployedResource(bpmnEntity);
        deployedProcess.setPublishedAt(OffsetDateTime.now());

        return (R) deploymentRepository.save(deployedProcess);

    }

    @Override
    public Optional<? extends DeploymentResource> findDeploymentResource(
            final int fileId) {

        return deploymentResourceRepository.findById(fileId);

    }

    @Override
    @SuppressWarnings("unchecked")
    public <R extends DeployedBpmn> R addDeployedBpmn(
            final int fileId,
            final String resourceName,
            final byte[] resource) {

        final var bpmn = new io.vanillabp.camunda8.deployment.jpa.DeployedBpmn();

        bpmn.setFileId(fileId);
        bpmn.setResource(resource);
        bpmn.setResourceName(resourceName);

        return (R) deploymentResourceRepository.save(bpmn);

    }

    @Override
    public List<? extends DeployedBpmn> getBpmnNotOfPackage(
            final String workflowModuleId,
            final int packageId) {

        return deployedBpmnRepository
                .findByDeployments_workflowModuleIdAndDeployments_PackageIdNot(workflowModuleId, packageId)
                .stream()
                .distinct() // Oracle doesn't support distinct queries including blob columns, hence the job is done here
                .toList();

    }

}
