package io.vanillabp.camunda8.deployment;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface DeployedProcessRepository extends CrudRepository<DeployedProcess, Long> {

    /**
     * <pre>
     * select distinct p.deployedResource from DeployedProcess p where not p.packageId = ?1
     * </pre>
     */
    List<DeployedBpmn> findDistinctDeployedResourceByPackageIdNot(int packageId);

}
