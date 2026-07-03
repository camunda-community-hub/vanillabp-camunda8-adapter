package io.vanillabp.camunda8.service;

import io.camunda.client.event.CamundaClientClosingEvent;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentAdapter;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.springboot.adapter.SpringDataUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * In Spring Boot tests, where application contexts are reused, we do not want to keep references to old
 * Repositories / CamundaClients. These should be recreated when required.
 */
public class ClientCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(ClientCleanupService.class);

    private final SpringDataUtil springDataUtil;
    private final Camunda8DeploymentAdapter deploymentAdapter;
    private final Camunda8TaskWiring taskWiring;

    public ClientCleanupService(SpringDataUtil springDataUtil,
                                Camunda8DeploymentAdapter deploymentAdapter,
                                Camunda8TaskWiring taskWiring) {
        this.springDataUtil = springDataUtil;
        this.deploymentAdapter = deploymentAdapter;
        this.taskWiring = taskWiring;
    }

    @EventListener
    public void onClientClosed(CamundaClientClosingEvent event) {
        logger.debug("Camunda client closed, cleaning up: {}", event);
        springDataUtil.doCleanup();
        deploymentAdapter.doCleanup();
        taskWiring.doCleanup();
    }
}
