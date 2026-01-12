package io.vanillabp.camunda8.wiring;

import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeLoopCharacteristics;
import io.vanillabp.springboot.adapter.Connectable;

public class Camunda8Connectable implements Connectable {

    public enum Type {
        TASK, USERTASK, USERTASK_ZEEBE
    };

    private final String workflowModuleId;

    private final Process process;

    private String versionInfo;

    private final String elementId;
    
    private final Type type;

    private final String taskDefinition;
    
    public Camunda8Connectable(
            final String workflowModuleId,
            final Process process,
            final String versionInfo,
            final String elementId,
            final Type type,
            final String taskDefinition,
            final ZeebeLoopCharacteristics loopCharacteristics) {

        this.workflowModuleId = workflowModuleId;
        this.process = process;
        this.versionInfo = versionInfo;
        this.elementId = elementId;
        this.taskDefinition = taskDefinition;
        this.type = type;

    }

    @Override
    public String getVersionInfo() {

        return versionInfo;

    }

    public void updateVersionInfo(
            final String versionInfo) {

        this.versionInfo = versionInfo;

    }

    public String getWorkflowModuleId() {

        return workflowModuleId;

    }

    @Override
    public String getElementId() {

        return elementId;

    }
    
    public Type getType() {
        
        return type;
        
    }
    
    @Override
    public boolean isExecutableProcess() {

        return process.isExecutable();

    }

    @Override
    public String getBpmnProcessId() {

        return process.getId();

    }
    
    @Override
    public String getTaskDefinition() {

        return taskDefinition;

    }
    
}