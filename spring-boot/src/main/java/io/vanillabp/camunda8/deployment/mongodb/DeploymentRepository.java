package io.vanillabp.camunda8.deployment.mongodb;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeploymentRepository extends MongoRepository<Deployment, String> {

    Optional<Deployment> findByDefinitionKey(long definitionKey);
    
}
