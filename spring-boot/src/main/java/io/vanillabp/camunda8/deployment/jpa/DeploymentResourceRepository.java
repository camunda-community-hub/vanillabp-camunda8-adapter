package io.vanillabp.camunda8.deployment.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository(DeploymentResourceRepository.BEAN_NAME)
public interface DeploymentResourceRepository extends JpaRepository<DeploymentResource, Integer> {

    String BEAN_NAME = "Camunda8DeploymentResourceRepository";

}
