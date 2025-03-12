package io.vanillabp.camunda8.deployment.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, DeploymentId> {

    Optional<Deployment> findByDefinitionKey(long definitionKey);
    
}
