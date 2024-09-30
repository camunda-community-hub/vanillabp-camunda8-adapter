package io.vanillabp.camunda8.deployment;

import io.camunda.zeebe.client.api.response.Process;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    
    private final DeploymentResourceRepository deploymentResourceRepository;

    public DeploymentService(
            final DeploymentRepository deploymentRepository,
            final DeploymentResourceRepository deploymentResourceRepository) {

        this.deploymentRepository = deploymentRepository;
        this.deploymentResourceRepository = deploymentResourceRepository;
        
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Retryable(
            retryFor = { OptimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class },
            maxAttempts = 100,
            backoff = @Backoff(delay = 100, maxDelay = 500))
    public DeployedBpmn addBpmn(
            final BpmnModelInstance model,
            final int fileId,
            final String resourceName) {

        final var previous = deploymentResourceRepository.findById(fileId);
        if (previous.isPresent()) {
            return (DeployedBpmn) previous.get();
        }

        final var outStream = new ByteArrayOutputStream();
        Bpmn.writeModelToStream(outStream, model);
        
        final var bpmn = new DeployedBpmn();
        bpmn.setFileId(fileId);
        bpmn.setResource(outStream.toByteArray());
        bpmn.setResourceName(resourceName);
        
        return deploymentResourceRepository.save(bpmn);
        
    }

    @Recover
    public DeployedBpmn recoverAddBpmn(
            final OptimisticLockingFailureException exception,
            final BpmnModelInstance model,
            final int fileId,
            final String resourceName) {

        return recoverAddBpmn(exception, model, fileId, resourceName);

    }

    @Recover
    public DeployedBpmn recoverAddBpmn(
            final ObjectOptimisticLockingFailureException exception,
            final BpmnModelInstance model,
            final int fileId,
            final String resourceName) {

        return recoverAddBpmn(exception, model, fileId, resourceName);

    }

    private DeployedBpmn recoverAddBpmn(
            final Exception exception,
            final BpmnModelInstance model,
            final int fileId,
            final String resourceName) {

        throw new RuntimeException(
                "Could not save BPMN '"
                        + resourceName
                        + "' in local DB due to stale OptimisticLockingFailureException", exception);

    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Retryable(
            retryFor = { OptimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class },
            maxAttempts = 100,
            backoff = @Backoff(delay = 100, maxDelay = 500))
    public DeployedProcess addProcess(
            final int packageId,
            final Process camunda8DeployedProcess,
            final DeployedBpmn bpmn) {

        final var versionedId = camunda8DeployedProcess.getProcessDefinitionKey();
        
        final var previous = deploymentRepository.findByDefinitionKey(versionedId);
        if ((previous.isPresent())
                && (previous.get().getPackageId() == packageId)) {
            return (DeployedProcess) previous.get();
        }

        final var deployedProcess = new DeployedProcess();
        
        deployedProcess.setDefinitionKey(versionedId);
        deployedProcess.setVersion(camunda8DeployedProcess.getVersion());
        deployedProcess.setPackageId(packageId);
        deployedProcess.setBpmnProcessId(camunda8DeployedProcess.getBpmnProcessId());
        deployedProcess.setDeployedResource(bpmn);
        deployedProcess.setPublishedAt(OffsetDateTime.now());
        
        return deploymentRepository.save(deployedProcess);
        
    }

    @Recover
    public DeployedProcess recoverAddProcess(
            final OptimisticLockingFailureException exception,
            final int packageId,
            final Process camunda8DeployedProcess,
            final DeployedBpmn bpmn) {

        return recoverAddProcess(exception, packageId, camunda8DeployedProcess, bpmn);

    }

    @Recover
    public DeployedProcess recoverAddProcess(
            final ObjectOptimisticLockingFailureException exception,
            final int packageId,
            final Process camunda8DeployedProcess,
            final DeployedBpmn bpmn) {

        return recoverAddProcess(exception, packageId, camunda8DeployedProcess, bpmn);

    }

    private DeployedProcess recoverAddProcess(
            final Exception exception,
            final int packageId,
            final Process camunda8DeployedProcess,
            final DeployedBpmn bpmn) {

        throw new RuntimeException(
                "Could not save Process '"
                        + camunda8DeployedProcess.getBpmnProcessId()
                        + "' in local DB due to stale OptimisticLockingFailureException", exception);

    }

    public List<DeployedBpmn> getBpmnNotOfPackage(final int packageId) {

        return deploymentResourceRepository
                .findByTypeAndDeployments_packageIdNot(DeployedBpmn.TYPE, packageId)
                .stream()
                .distinct() // Oracle doesn't support distinct queries including blob columns, hence the job is done here
                .toList();

    }

}
