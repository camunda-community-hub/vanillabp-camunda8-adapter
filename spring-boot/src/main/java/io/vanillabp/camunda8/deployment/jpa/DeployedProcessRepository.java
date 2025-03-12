package io.vanillabp.camunda8.deployment.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeployedProcessRepository extends JpaRepository<DeployedProcess, DeploymentId> {

}
