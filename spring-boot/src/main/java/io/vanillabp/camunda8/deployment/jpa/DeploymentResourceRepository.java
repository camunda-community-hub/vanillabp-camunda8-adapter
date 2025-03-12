package io.vanillabp.camunda8.deployment.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeploymentResourceRepository extends JpaRepository<DeploymentResource, Integer> {

    List<DeployedBpmn> findByTypeAndDeployments_packageIdNot(String type, int packageId);
    
}
