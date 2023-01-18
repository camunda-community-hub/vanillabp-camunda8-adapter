package org.camunda.community.vanillabp.c8.deployment;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeploymentResourceRepository extends CrudRepository<DeploymentResource, Integer> {

}
