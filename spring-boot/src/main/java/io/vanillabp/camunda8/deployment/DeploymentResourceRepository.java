package io.vanillabp.camunda8.deployment;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentResourceRepository extends CrudRepository<DeploymentResource, Integer> {

    List<DeployedBpmn> findByTypeAndDeployments_packageIdNot(String type, int packageId);
    
}
