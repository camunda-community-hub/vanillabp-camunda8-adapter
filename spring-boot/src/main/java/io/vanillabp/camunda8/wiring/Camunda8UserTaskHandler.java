package io.vanillabp.camunda8.wiring;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class Camunda8UserTaskHandler implements JobHandler {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8UserTaskHandler.class);

    private static final int MAX_ATTEMPTS_OF_ASSIGNING_USERTASKS = 1000;
    
    private final Map<String, Camunda8TaskHandler> taskHandlers = new HashMap<>();
    
    private final String workerId;
    
    public Camunda8UserTaskHandler(
            final String workerId) {
        
        this.workerId = workerId;
        
    }
    
    private String internalKey(
            final String tenantId,
            final String bpmnProcessId,
            final String elementId) {
        
        return tenantId + "#" + bpmnProcessId + "#" + elementId;
        
    }
    
    public void addTaskHandler(
            final String tenantId,
            final String bpmnProcessId,
            final String elementId,
            final Camunda8TaskHandler taskHandler) {

        final var key = internalKey(tenantId, bpmnProcessId, elementId);
        
        taskHandlers.put(key, taskHandler);
        
    }

    @Override
    public void handle(
            final JobClient client,
            final ActivatedJob job) throws Exception {

        final var key = internalKey(
                job.getTenantId(),
                job.getBpmnProcessId(),
                job.getElementId());
        final var taskHandler = taskHandlers.get(key);
        if (taskHandler == null) {
            if (job.getRetries() < MAX_ATTEMPTS_OF_ASSIGNING_USERTASKS) {
                logger.trace("No handler for BPMN process ID '{}' and element ID '{}' found! "
                        + "Will reject user-task to be handled by another workflow module (retry: {}/{}).",
                        job.getBpmnProcessId(),
                        job.getElementId(),
                        job.getRetries(),
                        MAX_ATTEMPTS_OF_ASSIGNING_USERTASKS);
                client
                        .newFailCommand(job)
                        .retries(job.getRetries() + 1)
                        .errorMessage("Worker '"
                                + workerId
                                + "' is not aware of BPMN process ID '"
                                + job.getBpmnProcessId()
                                + "' and user-task '"
                                + job.getElementId()
                                + "'!")
                        .retryBackoff(Duration.ofMillis(1)) // retry immediately
                        .send();
            } else {
                client
                        .newFailCommand(job)
                        .retries(0)
                        .errorMessage("No worker is aware of BPMN process ID '"
                                + job.getBpmnProcessId()
                                + "' and user-task '"
                                + job.getElementId()
                                + "'!")
                        .send();
                logger.error("No handler for BPMN process ID '{}' and element ID '{}' found "
                        + "even after {} retries! An incident is created.",
                        job.getBpmnProcessId(),
                        job.getElementId(),
                        MAX_ATTEMPTS_OF_ASSIGNING_USERTASKS);
            }
            return;
        }
        
        taskHandler.handle(client, job);

    }
    
}
