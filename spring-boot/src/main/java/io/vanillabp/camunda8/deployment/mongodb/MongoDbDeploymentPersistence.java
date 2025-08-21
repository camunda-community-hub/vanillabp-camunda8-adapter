package io.vanillabp.camunda8.deployment.mongodb;

import io.vanillabp.camunda8.deployment.DeployedBpmn;
import io.vanillabp.camunda8.deployment.Deployment;
import io.vanillabp.camunda8.deployment.DeploymentPersistence;
import io.vanillabp.camunda8.deployment.DeploymentResource;
import java.time.OffsetDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public class MongoDbDeploymentPersistence implements DeploymentPersistence {

    private final DeploymentResourceRepository deploymentResourceRepository;

    private final DeploymentRepository deploymentRepository;

    private final DeployedBpmnRepository deployedBpmnRepository;

    public MongoDbDeploymentPersistence(
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
    public <R extends io.vanillabp.camunda8.deployment.DeployedProcess> R updateMissingWorkflowModuleIdOfDeployedProcess(
            final R deployedProcess,
            final String workflowModuleId) {

        final var mongoDeployedProcess = (io.vanillabp.camunda8.deployment.mongodb.DeployedProcess) deployedProcess;
        mongoDeployedProcess.setWorkflowModuleId(workflowModuleId);
        return (R) deploymentRepository.save(mongoDeployedProcess);

    }

    @Override
    @SuppressWarnings("unchecked")
    public <R extends io.vanillabp.camunda8.deployment.DeployedProcess> R addDeployedProcess(
            final long definitionKey,
            final int version,
            final int packageId,
            final String workflowModuleId,
            final String bpmnProcessId,
            final DeployedBpmn bpmn,
            final OffsetDateTime publishedAt) {

        final var deployedProcess = new DeployedProcess();

        final var bpmnEntity = bpmn instanceof io.vanillabp.camunda8.deployment.mongodb.DeployedBpmn
                ? (io.vanillabp.camunda8.deployment.mongodb.DeployedBpmn) bpmn
                : deploymentResourceRepository.findById(
                        bpmn.getFileId()).orElseThrow(() -> new RuntimeException("Unknown BPMN file ID: " + bpmn.getFileId()));

        deployedProcess.setDefinitionKey(definitionKey);
        deployedProcess.setVersion(version);
        deployedProcess.setPackageId(packageId);
        deployedProcess.setWorkflowModuleId(workflowModuleId);
        deployedProcess.setBpmnProcessId(bpmnProcessId);
        deployedProcess.setDeployedResource(bpmnEntity);
        deployedProcess.setPublishedAt(OffsetDateTime.now());

        final var deployments = Optional
                .ofNullable(bpmnEntity.getDeployments())
                .orElse(new LinkedList<>());
        deployments.add(deployedProcess);
        deploymentResourceRepository.save(bpmnEntity);

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

        final var bpmn = new io.vanillabp.camunda8.deployment.mongodb.DeployedBpmn();

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
                .findAll()
                .stream()
                .filter(deployedBpmn -> deployedBpmn.getDeployments().stream().anyMatch(deployment ->
                        deployment.getWorkflowModuleId() != null
                        && workflowModuleId.equals(deployment.getWorkflowModuleId())
                        && deployment.getPackageId() != packageId))
                .distinct()
                .toList();

    }

}
