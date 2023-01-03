package io.vanillabp.camunda8;

import io.camunda.zeebe.spring.client.EnableZeebeClient;
import io.camunda.zeebe.spring.client.jobhandling.DefaultCommandExceptionHandlingStrategy;
import io.camunda.zeebe.spring.client.lifecycle.ZeebeClientLifecycle;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentAdapter;
import io.vanillabp.camunda8.deployment.DeployedProcessRepository;
import io.vanillabp.camunda8.deployment.DeploymentRepository;
import io.vanillabp.camunda8.deployment.DeploymentResourceRepository;
import io.vanillabp.camunda8.deployment.DeploymentService;
import io.vanillabp.camunda8.service.Camunda8ProcessService;
import io.vanillabp.camunda8.wiring.Camunda8TaskHandler;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.camunda8.wiring.Camunda8UserTaskHandler;
import io.vanillabp.camunda8.wiring.Camunda8Connectable.Type;
import io.vanillabp.springboot.adapter.AdapterConfigurationBase;
import io.vanillabp.springboot.adapter.SpringDataUtil;
import io.vanillabp.springboot.parameters.MethodParameter;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.data.repository.CrudRepository;

import java.lang.reflect.Method;
import java.util.List;

@AutoConfigurationPackage(basePackageClasses = Camunda8AdapterConfiguration.class)
@EnableZeebeClient
public class Camunda8AdapterConfiguration extends AdapterConfigurationBase<Camunda8ProcessService<?>> {

    @Value("${workerId}")
    private String workerId;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ZeebeClientLifecycle clientLifecycle;
    
    @Autowired
    private DefaultCommandExceptionHandlingStrategy commandExceptionHandlingStrategy;

    @Autowired
    private DeploymentRepository deploymentRepository;

    @Autowired
    private DeployedProcessRepository deployedProcessRepository;

    @Autowired
    private DeploymentResourceRepository deploymentResourceRepository;

    @Bean
    public Camunda8DeploymentAdapter camunda8Adapter(
            final DeploymentService deploymentService,
            final Camunda8TaskWiring camunda8TaskWiring) {

        return new Camunda8DeploymentAdapter(
                deploymentService,
                clientLifecycle,
                camunda8TaskWiring);

    }

    @Bean
    public Camunda8TaskWiring camunda8TaskWiring(
            final SpringDataUtil springDataUtil,
            final Camunda8UserTaskHandler userTaskHandler,
            final ObjectProvider<Camunda8TaskHandler> taskHandlers) {

        return new Camunda8TaskWiring(
                springDataUtil,
                applicationContext,
                workerId,
                userTaskHandler,
                taskHandlers,
                getConnectableServices());

    }

    @Bean
    public DeploymentService deploymentService(
            final SpringDataUtil springDataUtil) {

        return new DeploymentService(
                springDataUtil,
                deploymentRepository,
                deploymentResourceRepository,
                deployedProcessRepository);

    }

    @Bean
    public Camunda8UserTaskHandler userTaskHandler() {

        return new Camunda8UserTaskHandler();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Camunda8TaskHandler camunda8TaskHandler(
            final SpringDataUtil springDataUtil,
            final CrudRepository<Object, String> repository,
            final Type taskType,
            final String taskDefinition,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters,
            final String idPropertyName) {
        
        return new Camunda8TaskHandler(
                taskType,
                deploymentService(springDataUtil),
                commandExceptionHandlingStrategy,
                repository,
                bean,
                method,
                parameters,
                idPropertyName);
        
    }
    
    @SuppressWarnings("unchecked")
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public <DE> Camunda8ProcessService<?> camundaProcessService(
            final SpringDataUtil springDataUtil,
            final InjectionPoint injectionPoint) throws Exception {

        return registerProcessService(
                springDataUtil,
                injectionPoint,
                (workflowDomainEntityRepository, workflowDomainEntityClass) ->
                new Camunda8ProcessService<DE>(
                        (CrudRepository<DE, String>) workflowDomainEntityRepository,
                        domainEntity -> springDataUtil.getId(domainEntity),
                        (Class<DE>) workflowDomainEntityClass)
            );

    }
    
}
