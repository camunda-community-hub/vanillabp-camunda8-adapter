package io.vanillabp.camunda8.deployment.mongodb;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository(DeploymentRepository.BEAN_NAME)
public interface DeploymentRepository extends MongoRepository<Deployment, String> {

    String BEAN_NAME = "Camunda8DeploymentRepository";

    Optional<Deployment> findByDefinitionKey(long definitionKey);
    
}
