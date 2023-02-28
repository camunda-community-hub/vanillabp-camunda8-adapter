package io.vanillabp.camunda8.deployment;

import javax.persistence.Column;
import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;

@Entity
@DiscriminatorValue(DeployedProcess.TYPE)
public class DeployedProcess extends Deployment {
    
    public static final String TYPE = "PROCESS";
    
    /** the BPMN process id of the process */
    @Column(name = "BPMN_PROCESS_ID")
    private String bpmnProcessId;

    public String getBpmnProcessId() {
        return bpmnProcessId;
    }

    public void setBpmnProcessId(String bpmnProcessId) {
        this.bpmnProcessId = bpmnProcessId;
    }

}
