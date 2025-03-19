package io.vanillabp.camunda8.deployment.mongodb;

import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = DeploymentResource.COLLECTION_NAME)
public class DeployedBpmn extends DeploymentResource
        implements io.vanillabp.camunda8.deployment.DeployedBpmn {

}
