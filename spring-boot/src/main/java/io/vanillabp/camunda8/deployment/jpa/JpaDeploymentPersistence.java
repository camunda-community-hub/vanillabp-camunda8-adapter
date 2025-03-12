package io.vanillabp.camunda8.deployment.jpa;

import io.vanillabp.camunda8.deployment.DeployedBpmn;
import io.vanillabp.camunda8.deployment.Deployment;
import io.vanillabp.camunda8.deployment.DeploymentPersistence;
import io.vanillabp.camunda8.deployment.DeploymentResource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class JpaDeploymentPersistence implements DeploymentPersistence {

    private final DeploymentResourceRepository deploymentResourceRepository;

    private final DeploymentRepository deploymentRepository;

    public JpaDeploymentPersistence(
            final DeploymentResourceRepository deploymentResourceRepository,
            final DeploymentRepository deploymentRepository) {

        this.deploymentResourceRepository = deploymentResourceRepository;
        this.deploymentRepository = deploymentRepository;

    }

    @Override
    public Optional<? extends Deployment> findDeployedProcess(
            final long definitionKey) {

        return deploymentRepository.findByDefinitionKey(definitionKey);

    }

    @Override
    @SuppressWarnings("unchecked")
    public <R extends io.vanillabp.camunda8.deployment.DeployedProcess> R addDeployedProcess(
            final long definitionKey,
            final int version,
            final int packageId,
            final String bpmnProcessId,
            final DeployedBpmn bpmn,
            final OffsetDateTime publishedAt) {

        final var deployedProcess = new DeployedProcess();

        final var bpmnEntity = bpmn instanceof io.vanillabp.camunda8.deployment.jpa.DeployedBpmn
                ? (io.vanillabp.camunda8.deployment.jpa.DeployedBpmn) bpmn
                : deploymentResourceRepository.findById(bpmn.getFileId()).orElse(null);

        deployedProcess.setDefinitionKey(definitionKey);
        deployedProcess.setVersion(version);
        deployedProcess.setPackageId(packageId);
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
    public <R extends io.vanillabp.camunda8.deployment.DeployedBpmn> R addDeployedBpmn(
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
            final int packageId) {

        return deploymentResourceRepository
                .findByTypeAndDeployments_packageIdNot(io.vanillabp.camunda8.deployment.jpa.DeployedBpmn.TYPE, packageId)
                .stream()
                .distinct() // Oracle doesn't support distinct queries including blob columns, hence the job is done here
                .toList();

    }

}
