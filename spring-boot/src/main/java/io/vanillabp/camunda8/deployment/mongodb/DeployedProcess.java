package io.vanillabp.camunda8.deployment.mongodb;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = Deployment.COLLECTION_NAME)
public class DeployedProcess extends Deployment
        implements io.vanillabp.camunda8.deployment.DeployedProcess {

    /** the BPMN process id of the process */
    @Field(name = "C8D_BPMN_PROCESS_ID")
    private String bpmnProcessId;

    public String getBpmnProcessId() {
        return bpmnProcessId;
    }

    public void setBpmnProcessId(String bpmnProcessId) {
        this.bpmnProcessId = bpmnProcessId;
    }

}
