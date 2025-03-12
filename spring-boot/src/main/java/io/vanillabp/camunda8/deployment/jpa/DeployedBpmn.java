package io.vanillabp.camunda8.deployment.jpa;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(DeployedBpmn.TYPE)
public class DeployedBpmn extends DeploymentResource
        implements io.vanillabp.camunda8.deployment.DeployedBpmn {

    public static final String TYPE = "BPMN";
    
}
