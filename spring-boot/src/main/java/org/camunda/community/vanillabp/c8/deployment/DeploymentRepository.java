package org.camunda.community.vanillabp.c8.deployment;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentRepository extends CrudRepository<Deployment, Long> {

    /**
     * <pre>
     * select distinct p.deployedResource from DeployedProcess p where not p.packageId = ?1
     * </pre>
     */
    List<DeployedBpmn> findDistinctDeployedResourceByPackageIdNot(int packageId);

}
