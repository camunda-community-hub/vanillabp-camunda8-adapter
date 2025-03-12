package io.vanillabp.camunda8.deployment.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(DeployedProcess.TYPE)
public class DeployedProcess extends Deployment
        implements io.vanillabp.camunda8.deployment.DeployedProcess {
    
    public static final String TYPE = "PROCESS";
    
    /** the BPMN process id of the process */
    @Column(name = "C8D_BPMN_PROCESS_ID")
    private String bpmnProcessId;

    public String getBpmnProcessId() {
        return bpmnProcessId;
    }

    public void setBpmnProcessId(String bpmnProcessId) {
        this.bpmnProcessId = bpmnProcessId;
    }

}
