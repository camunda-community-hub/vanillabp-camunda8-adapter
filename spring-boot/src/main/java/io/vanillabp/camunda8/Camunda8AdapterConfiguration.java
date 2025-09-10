package io.vanillabp.camunda8;

import io.vanillabp.camunda8.config.CamundaAutoConfiguration;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentAdapter;
import io.vanillabp.camunda8.service.Camunda8ProcessService;
import io.vanillabp.camunda8.service.Camunda8TransactionAspect;
import io.vanillabp.camunda8.service.Camunda8TransactionProcessor;
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
import java.lang.reflect.Method;
import java.util.List;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.repository.CrudRepository;
import org.springframework.retry.annotation.EnableRetry;

@AutoConfigurationPackage(basePackageClasses = Camunda8AdapterConfiguration.class)
@AutoConfigureBefore(name = {
        "io.camunda.spring.client.configuration.CamundaAutoConfiguration" // official client
})
@EnableConfigurationProperties(Camunda8VanillaBpProperties.class)
@EnableRetry
@Import(CamundaAutoConfiguration.class)
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
    private Camunda8VanillaBpProperties camunda8Properties;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @PostConstruct
    public void init() {
        
        logger.debug("Will use SpringDataUtil class '{}'",
                AopProxyUtils.ultimateTargetClass(springDataUtil));
        
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public Camunda8TransactionAspect camunda8TransactionAspect() {

        return new Camunda8TransactionAspect(eventPublisher);

    }

    @Override
    public String getAdapterId() {
        
        return ADAPTER_ID;
        
    }
    
    @Bean
    public Camunda8DeploymentAdapter camunda8Adapter(
            final VanillaBpProperties properties,
            final Camunda8TaskWiring camunda8TaskWiring) {

        return new Camunda8DeploymentAdapter(
                applicationName,
                properties,
                camunda8Properties,
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
                workerId,
                camunda8Properties,
                userTaskHandler,
                taskHandlers,
                getConnectableServices());

    }

    @Bean
    public Camunda8UserTaskHandler userTaskHandler() {

        return new Camunda8UserTaskHandler(workerId);
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Camunda8TaskHandler camunda8TaskHandler(
            final SpringDataUtil springDataUtil,
            final CrudRepository<Object, Object> repository, // validate when actually called not during startup
            final Type taskType,
            final String taskDefinition,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters,
            final String idPropertyName,
            final String tenantId,
            final String workflowModuleId,
            final String bpmnProcessId) {
        
        return new Camunda8TaskHandler(
                taskType,
                repository,
                bean,
                method,
                parameters,
                idPropertyName,
                tenantId,
                workflowModuleId,
                bpmnProcessId,
                camunda8Properties.isTaskIdAsHexString(workflowModuleId));
        
    }
    
    @Override
    public <DE> Camunda8ProcessService<?> newProcessServiceImplementation(
            final SpringDataUtil springDataUtil,
            final Class<DE> workflowAggregateClass,
            final Class<?> workflowAggregateIdClass,
            final CrudRepository<DE, Object> workflowAggregateRepository) {

        final var result = new Camunda8ProcessService<DE>(
                camunda8Properties,
                eventPublisher,
                workflowAggregateRepository,
                springDataUtil::getId,
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

    /*
     * https://www.tirasa.net/en/blog/dynamic-spring-s-transactional-2020-edition
     */
    /*
    @Bean
    public static BeanFactoryPostProcessor camunda8TransactionInterceptorInjector() {

        return beanFactory -> {
            String[] names = beanFactory.getBeanNamesForType(TransactionInterceptor.class);
            for (String name : names) {
                BeanDefinition bd = beanFactory.getBeanDefinition(name);
                bd.setBeanClassName(Camunda8TransactionInterceptor.class.getName());
                bd.setFactoryBeanName(null);
                bd.setFactoryMethodName(null);
            }
        };

    }
    */

    @Bean
    public Camunda8TransactionProcessor camunda8TransactionProcessor() {

        return new Camunda8TransactionProcessor();

    }

}
