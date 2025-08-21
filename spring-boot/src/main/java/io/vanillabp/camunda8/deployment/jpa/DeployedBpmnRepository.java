package io.vanillabp.camunda8.deployment.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository(DeployedBpmnRepository.BEAN_NAME)
public interface DeployedBpmnRepository extends JpaRepository<DeployedBpmn, Integer> {

    String BEAN_NAME = "Camunda8DeployedBpmnRepository";

    List<DeployedBpmn> findByDeployments_workflowModuleIdAndDeployments_PackageIdNot(String workflowModuleId, int packageId);

}
