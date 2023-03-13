package io.vanillabp.camunda8.deployment;

import org.springframework.data.repository.CrudRepository;

public interface DeployedProcessRepository extends CrudRepository<DeployedProcess, Long> {

}
