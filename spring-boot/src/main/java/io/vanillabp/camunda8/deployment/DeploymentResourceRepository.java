package io.vanillabp.camunda8.deployment;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeploymentResourceRepository extends CrudRepository<DeploymentResource, Integer> {

}
