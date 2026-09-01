package io.vanillabp.camunda8;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.impl.CamundaObjectMapper;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentAdapter;
import io.vanillabp.camunda8.service.Camunda8ProcessService;
import io.vanillabp.camunda8.service.Camunda8TransactionAspect;
import io.vanillabp.camunda8.service.Camunda8TransactionProcessor;
import io.vanillabp.camunda8.service.ClientCleanupService;
import io.vanillabp.camunda8.wiring.Camunda8Connectable.Type;
import io.vanillabp.camunda8.wiring.Camunda8TaskHandler;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.camunda8.wiring.Camunda8UserTaskHandler;
import io.vanillabp.camunda8.wiring.Retries;
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
import io.camunda.client.spring.configuration.CamundaAutoConfiguration;
import io.camunda.client.spring.configuration.JsonMapperConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.repository.CrudRepository;
import org.springframework.retry.annotation.EnableRetry;

/*
 * The reference to Camunda's own auto-configuration is a class literal rather than a name: a string
 * silently stops matching when the target moves, and Camunda did move things around when it split the
 * starter into camunda-spring-boot-3-starter and camunda-spring-boot-starter. Ordering metadata is read
 * from the byte code, so a class literal is safe even if the class were absent at runtime.
 *
 * See also io.vanillabp.camunda8.config.DisableCamundaSpringAutoConfigurationImportFilter, which
 * suppresses Camunda's job-worker annotation processing so this adapter can do the wiring itself.
 */
@AutoConfiguration(before = { CamundaAutoConfiguration.class, JsonMapperConfiguration.class })
@AutoConfigurationPackage(basePackageClasses = Camunda8AdapterConfiguration.class)
@EnableConfigurationProperties(Camunda8VanillaBpProperties.class)
@EnableRetry
public class Camunda8AdapterConfiguration extends AdapterConfigurationBase<Camunda8ProcessService<?>> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8AdapterConfiguration.class);

    static {
        Camunda8DeploymentAdapter.initializeCrossCuttingProperties();
    }

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

    /*
     * An ObjectProvider rather than the bean itself: this class contributes the JsonMapper bean below, so a
     * direct injection would make the configuration depend on a bean of its own - Spring reports that as
     * BeanCurrentlyInCreationException and no application using this adapter would start. The provider is
     * only a handle, it resolves when newProcessServiceImplementation(..) asks for the mapper.
     */
    @Autowired
    private ObjectProvider<JsonMapper> camundaJsonMapper;

    @PostConstruct
    public void init() {
        
        logger.debug("Will use SpringDataUtil class '{}'",
                AopProxyUtils.ultimateTargetClass(springDataUtil));
        
    }

    /**
     * Camunda's own {@code JsonMapperConfiguration} cannot see the application's Jackson setup on Spring
     * Boot 4: it injects a <b>Jackson 2</b> {@code ObjectMapper} with {@code @Autowired(required = false)},
     * and Boot 4 auto-configures Jackson 3. Its {@code @ConditionalOnMissingBean} fallback is a
     * {@code CamundaObjectMapper} with no modules registered, which cannot serialize
     * {@code OffsetDateTime}, {@code Instant} or {@code LocalDate} at all.
     * <p>
     * Providing the bean here closes that gap. Camunda's {@code JsonMapperConfiguration} is reached through
     * {@code @ImportAutoConfiguration} on {@code CamundaAutoConfiguration}, which makes it an
     * auto-configuration in its own right - being ordered before {@code CamundaAutoConfiguration} therefore
     * says nothing about it. Hence {@code JsonMapperConfiguration} is named in this class'
     * {@code @AutoConfiguration(before = ..)} as well: that is what puts our bean definition in the context
     * first, so Camunda's {@code @ConditionalOnMissingBean} backs off.
     * {@code io.vanillabp.camunda8.wiring.JsonMapperPrecedenceTest} verifies it rather than assuming it.
     * <p>
     * If the application has no Jackson 3 mapper - possible for a module without any web or JSON starter -
     * the behaviour is unchanged: the same module-less {@code CamundaObjectMapper} Camunda would have
     * built. {@code ObjectProvider} is used rather than {@code @ConditionalOnBean} on purpose: the lookup
     * happens when the bean is created, so it does not depend on auto-configuration ordering relative to
     * Boot's Jackson auto-configuration.
     * <p>
     * {@code @ConditionalOnMissingBean} keeps an application's own {@link JsonMapper} bean in charge -
     * regular {@code @Configuration} classes are processed before any auto-configuration.
     */
    @Bean
    @ConditionalOnMissingBean(JsonMapper.class)
    public JsonMapper camunda8JsonMapper(
            final ObjectProvider<tools.jackson.databind.json.JsonMapper> applicationJsonMapper) {

        final var jackson3 = applicationJsonMapper.getIfAvailable();
        if (jackson3 == null) {
            logger.info(
                    "No Jackson 3 mapper found, Zeebe variables will be serialized by a mapper without any "
                    + "modules registered. Java 8 date and time types cannot be used in workflow "
                    + "aggregates in that case - add a JSON or web starter to change this.");
            return new CamundaObjectMapper();
        }
        return new Camunda8Jackson3JsonMapper(jackson3);

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
            final Camunda8TaskWiring camunda8TaskWiring,
            final ApplicationEventPublisher applicationEventPublisher) {

        return new Camunda8DeploymentAdapter(
                applicationName,
                properties,
                camunda8Properties,
                camunda8TaskWiring,
                applicationEventPublisher);

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
            final String bpmnProcessId,
            final Retries retries,
            final CamundaClient client) {
        
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
                retries,
                camunda8Properties.isTaskIdAsHexString(workflowModuleId),
                camunda8Properties.isReportErrorsAsStackTrace(workflowModuleId),
                client);
        
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
                camundaJsonMapper.getObject(),
                workflowAggregateRepository,
                springDataUtil::getId,
                workflowAggregateClass,
                springDataUtil.getIdName(workflowAggregateClass));

        putConnectableService(workflowAggregateClass, result);
        
        return result;
        
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringBeanUtil vanillabpSpringBeanUtil(
            final ApplicationContext applicationContext) {

        return new SpringBeanUtil(applicationContext);

    }

    @Bean
    public Camunda8TransactionProcessor camunda8TransactionProcessor() {

        return new Camunda8TransactionProcessor();

    }

    @Bean
    public ClientCleanupService clientCleanupService(Camunda8DeploymentAdapter deploymentAdapter,
                                                     Camunda8TaskWiring taskWiring) {
        return new ClientCleanupService(springDataUtil, deploymentAdapter, taskWiring);
    }
}
