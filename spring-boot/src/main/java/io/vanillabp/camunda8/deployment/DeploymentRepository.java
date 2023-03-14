package io.vanillabp.camunda8.deployment;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeploymentRepository extends CrudRepository<Deployment, DeploymentId> {

    /**
     * <pre>
     * select distinct p.deployedResource from DeployedProcess p where not p.packageId = ?1
     * </pre>
     */
    List<DeployedBpmn> findDistinctDeployedResourceByPackageIdNot(int packageId);

    Optional<Deployment> findByDefinitionKey(long definitionKey);
    
}
