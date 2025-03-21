package io.vanillabp.camunda8.deployment.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository(DeploymentResourceRepository.BEAN_NAME)
public interface DeploymentResourceRepository extends MongoRepository<DeploymentResource, Integer> {

    String BEAN_NAME = "Camunda8DeploymentResourceRepository";

}
