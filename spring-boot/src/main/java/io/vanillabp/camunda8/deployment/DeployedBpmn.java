package io.vanillabp.camunda8.deployment;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue(DeployedBpmn.TYPE)
public class DeployedBpmn extends DeploymentResource {

    public static final String TYPE = "BPMN";
    
}
