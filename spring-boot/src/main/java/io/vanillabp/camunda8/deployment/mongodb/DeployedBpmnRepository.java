package io.vanillabp.camunda8.deployment.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository(DeployedBpmnRepository.BEAN_NAME)
public interface DeployedBpmnRepository extends MongoRepository<DeployedBpmn, Integer> {

    String BEAN_NAME = "Camunda8DeployedBpmnRepository";

}
