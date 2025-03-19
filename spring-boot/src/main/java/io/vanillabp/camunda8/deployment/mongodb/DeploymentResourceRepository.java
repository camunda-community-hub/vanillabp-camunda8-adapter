package io.vanillabp.camunda8.deployment.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeploymentResourceRepository extends MongoRepository<DeploymentResource, Integer> {
}
