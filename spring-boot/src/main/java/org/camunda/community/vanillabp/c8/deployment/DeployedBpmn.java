package org.camunda.community.vanillabp.c8.deployment;

import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;

@Entity
@DiscriminatorValue("BPMN")
public class DeployedBpmn extends DeploymentResource {

}
