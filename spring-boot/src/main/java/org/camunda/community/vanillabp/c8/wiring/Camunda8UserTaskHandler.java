package org.camunda.community.vanillabp.c8.wiring;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;

public class Camunda8UserTaskHandler implements JobHandler {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8UserTaskHandler.class);

    private final Map<String, Camunda8TaskHandler> taskHandlers = new HashMap<>();
    
    private String internalKey(
            final String bpmnProcessId,
            final String elementId) {
        
        return bpmnProcessId + "#" + elementId;
        
    }
    
    public void addTaskHandler(
            final String bpmnProcessId,
            final String elementId,
            final Camunda8TaskHandler taskHandler) {
        
        final var key = internalKey(bpmnProcessId, elementId);
        
        taskHandlers.put(key, taskHandler);
        
    }

    @Override
    public void handle(
            final JobClient client,
            final ActivatedJob job) throws Exception {

        final var key = internalKey(
                job.getBpmnProcessId(),
                job.getElementId());
        final var taskHandler = taskHandlers.get(key);
        if (taskHandler == null) {
            logger.debug("No handler for BPMN process ID '{}' and element ID '{}' found!",
                    job.getBpmnProcessId(), job.getElementId());
            return;
        }
        
        taskHandler.handle(client, job);

    }
    
}
