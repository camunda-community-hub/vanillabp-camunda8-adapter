package io.vanillabp.camunda8.deployment;

import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;

@Entity
@DiscriminatorValue("BPMN")
public class DeployedBpmn extends DeploymentResource {

}
