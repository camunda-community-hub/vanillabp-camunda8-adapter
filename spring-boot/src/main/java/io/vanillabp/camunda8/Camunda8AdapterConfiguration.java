package io.vanillabp.camunda8;

import io.camunda.zeebe.spring.client.CamundaAutoConfiguration;
import io.camunda.zeebe.spring.client.jobhandling.DefaultCommandExceptionHandlingStrategy;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentAdapter;
import io.vanillabp.camunda8.deployment.DeploymentRepository;
import io.vanillabp.camunda8.deployment.DeploymentResourceRepository;
import io.vanillabp.camunda8.deployment.DeploymentService;
import io.vanillabp.camunda8.service.Camunda8ProcessService;
import io.vanillabp.camunda8.wiring.Camunda8Connectable.Type;
import io.vanillabp.camunda8.wiring.Camunda8TaskHandler;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.camunda8.wiring.Camunda8UserTaskHandler;
import io.vanillabp.springboot.adapter.AdapterConfigurationBase;
import io.vanillabp.springboot.adapter.SpringBeanUtil;
import io.vanillabp.springboot.adapter.SpringDataUtil;
import io.vanillabp.springboot.adapter.VanillaBpProperties;
import io.vanillabp.springboot.parameters.MethodParameter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.data.repository.CrudRepository;

import java.lang.reflect.Method;
import java.util.List;

@AutoConfigurationPackage(basePackageClasses = Camunda8AdapterConfiguration.class)
@AutoConfigureBefore(CamundaAutoConfiguration.class)
@EnableConfigurationProperties(Camunda8VanillaBpProperties.class)
public class Camunda8AdapterConfiguration extends AdapterConfigurationBase<Camunda8ProcessService<?>> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8AdapterConfiguration.class);
    
    public static final String ADAPTER_ID = "camunda8";

    @Value("${workerId}")
    private String workerId;

    @Value("${spring.application.name:@null}")
    private String applicationName;

    @Autowired
    private SpringDataUtil springDataUtil; // ensure persistence is up and running

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DefaultCommandExceptionHandlingStrategy commandExceptionHandlingStrategy;

    @Autowired
    private DeploymentRepository deploymentRepository;

    @Autowired
    private DeploymentResourceRepository deploymentResourceRepository;

    @Autowired
    private Camunda8VanillaBpProperties camunda8Properties;

    @PostConstruct
    public void init() {
        
        logger.debug("Will use SpringDataUtil class '{}'",
                AopProxyUtils.ultimateTargetClass(springDataUtil));
        
    }

    @Override
    public String getAdapterId() {
        
        return ADAPTER_ID;
        
    }
    
    @Bean
    public Camunda8DeploymentAdapter camunda8Adapter(
            final VanillaBpProperties properties,
            final DeploymentService deploymentService,
            final Camunda8TaskWiring camunda8TaskWiring) {

        return new Camunda8DeploymentAdapter(
                applicationName,
                properties,
                camunda8Properties,
                deploymentService,
                camunda8TaskWiring);

    }

    @Bean
    public Camunda8TaskWiring camunda8TaskWiring(
            final SpringDataUtil springDataUtil,
            final SpringBeanUtil springBeanUtil,
            final Camunda8UserTaskHandler userTaskHandler,
            final ObjectProvider<Camunda8TaskHandler> taskHandlers) {

        return new Camunda8TaskWiring(
                springDataUtil,
                applicationContext,
                springBeanUtil,
                applicationName,
                workerId,
                userTaskHandler,
                taskHandlers,
                getConnectableServices());

    }

    @Bean
    public DeploymentService deploymentService(
            final SpringDataUtil springDataUtil) {

        return new DeploymentService(
                deploymentRepository,
                deploymentResourceRepository);

    }

    @Bean
    public Camunda8UserTaskHandler userTaskHandler() {

        return new Camunda8UserTaskHandler(workerId);
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Camunda8TaskHandler camunda8TaskHandler(
            final SpringDataUtil springDataUtil,
            final CrudRepository<Object, Object> repository,
            final Type taskType,
            final String taskDefinition,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters,
            final String idPropertyName) {
        
        return new Camunda8TaskHandler(
                taskType,
                commandExceptionHandlingStrategy,
                repository,
                bean,
                method,
                parameters,
                idPropertyName);
        
    }
    
    @Override
    public <DE> Camunda8ProcessService<?> newProcessServiceImplementation(
            final SpringDataUtil springDataUtil,
            final Class<DE> workflowAggregateClass,
            final Class<?> workflowAggregateIdClass,
            final CrudRepository<DE, Object> workflowAggregateRepository) {

        final var result = new Camunda8ProcessService<DE>(
                camunda8Properties,
                workflowAggregateRepository,
                workflowAggregate -> springDataUtil.getId(workflowAggregate),
                workflowAggregateClass);

        putConnectableService(workflowAggregateClass, result);
        
        return result;
        
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringBeanUtil vanillabpSpringBeanUtil(
            final ApplicationContext applicationContext) {

        return new SpringBeanUtil(applicationContext);

    }

}
