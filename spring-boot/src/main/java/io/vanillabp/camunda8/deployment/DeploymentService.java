package io.vanillabp.camunda8.deployment;

import io.camunda.client.api.response.Process;
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

    private final DeploymentPersistence persistence;

    public DeploymentService(
            final DeploymentPersistence persistence) {

        this.persistence = persistence;
        
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Retryable(
            retryFor = { OptimisticLockingFailureException.class, ObjectOptimisticLockingFailureException.class },
            maxAttempts = 100,
            backoff = @Backoff(delay = 200, maxDelay = 1000, multiplier = 1.5, random = true))
    public DeployedBpmn addBpmn(
            final BpmnModelInstance model,
            final int fileId,
            final String resourceName) {

        final var previous = persistence.findDeploymentResource(fileId);
        if (previous.isPresent()) {
            return (DeployedBpmn) previous.get();
        }

        final var outStream = new ByteArrayOutputStream();
        Bpmn.writeModelToStream(outStream, model);

        return persistence.addDeployedBpmn(
                fileId,
                resourceName,
                outStream.toByteArray());

    }

    @Recover
    public DeployedBpmn recoverAddBpmn(
            final OptimisticLockingFailureException exception,
            final BpmnModelInstance model,
            final int fileId,
            final String resourceName) {

        return staleRecoverAddBpmn(exception, model, fileId, resourceName);

    }

    @Recover
    public DeployedBpmn recoverAddBpmn(
            final ObjectOptimisticLockingFailureException exception,
            final BpmnModelInstance model,
            final int fileId,
            final String resourceName) {

        return staleRecoverAddBpmn(exception, model, fileId, resourceName);

    }

    private DeployedBpmn staleRecoverAddBpmn(
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
            backoff = @Backoff(delay = 200, maxDelay = 1000, multiplier = 1.5, random = true))
    public DeployedProcess addProcess(
            final String workflowModuleId,
            final int packageId,
            final Process camunda8DeployedProcess,
            final DeployedBpmn bpmn) {

        final var versionedId = camunda8DeployedProcess.getProcessDefinitionKey();

        final var previous = persistence.findDeployedProcess(versionedId);
        if (previous.isPresent()
                && (previous.get().getVersion() == camunda8DeployedProcess.getVersion())) {
            var result = (DeployedProcess) previous.get();
            if (result.getWorkflowModuleId() == null) {
                result = persistence.updateMissingWorkflowModuleIdOfDeployedProcess(result, workflowModuleId);
            }
            return result;
        }

        return persistence.addDeployedProcess(
                versionedId,
                camunda8DeployedProcess.getVersion(),
                packageId,
                workflowModuleId,
                camunda8DeployedProcess.getBpmnProcessId(),
                bpmn,
                OffsetDateTime.now());

    }

    @Recover
    public DeployedProcess recoverAddProcess(
            final OptimisticLockingFailureException exception,
            final String workflowModuleId,
            final int packageId,
            final Process camunda8DeployedProcess,
            final DeployedBpmn bpmn) {

        return staleRecoverAddProcess(exception, workflowModuleId, packageId, camunda8DeployedProcess, bpmn);

    }

    @Recover
    public DeployedProcess recoverAddProcess(
            final ObjectOptimisticLockingFailureException exception,
            final String workflowModuleId,
            final int packageId,
            final Process camunda8DeployedProcess,
            final DeployedBpmn bpmn) {

        return staleRecoverAddProcess(exception, workflowModuleId, packageId, camunda8DeployedProcess, bpmn);

    }

    private DeployedProcess staleRecoverAddProcess(
            final Exception exception,
            final String workflowModuleId,
            final int packageId,
            final Process camunda8DeployedProcess,
            final DeployedBpmn bpmn) {

        throw new RuntimeException(
                "Could not save Process '"
                        + camunda8DeployedProcess.getBpmnProcessId()
                        + "' of workflow module '"
                        + workflowModuleId
                        + "' in local DB due to stale OptimisticLockingFailureException", exception);

    }

    public List<? extends DeployedBpmn> getBpmnNotOfPackage(
            final String workflowModuleId,
            final int packageId) {

        return persistence.getBpmnNotOfPackage(workflowModuleId, packageId);

    }

}
