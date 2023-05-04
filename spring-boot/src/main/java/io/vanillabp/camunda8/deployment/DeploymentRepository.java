package io.vanillabp.camunda8.deployment;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeploymentRepository extends CrudRepository<Deployment, DeploymentId> {

    Optional<Deployment> findByDefinitionKey(long definitionKey);
    
}
