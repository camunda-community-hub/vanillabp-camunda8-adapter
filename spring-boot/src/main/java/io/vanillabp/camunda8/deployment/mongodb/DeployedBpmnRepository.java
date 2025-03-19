package io.vanillabp.camunda8.deployment.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DeployedBpmnRepository extends MongoRepository<DeployedBpmn, Integer> {

}
