package io.vanillabp.camunda8.deployment;

import io.camunda.client.CamundaClient;
import io.camunda.client.event.CamundaClientCreatedEvent;
import io.camunda.zeebe.model.bpmn.impl.BpmnModelInstanceImpl;
import io.camunda.zeebe.model.bpmn.impl.BpmnParser;
import io.camunda.zeebe.model.bpmn.instance.BusinessRuleTask;
import io.camunda.zeebe.model.bpmn.instance.EndEvent;
import io.camunda.zeebe.model.bpmn.instance.IntermediateThrowEvent;
import io.camunda.zeebe.model.bpmn.instance.MessageEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.SendTask;
import io.camunda.zeebe.model.bpmn.instance.ServiceTask;
import io.camunda.zeebe.model.bpmn.instance.SignalEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.StartEvent;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeVersionTag;
import io.vanillabp.camunda8.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.Camunda8VanillaBpProperties;
import io.vanillabp.camunda8.service.Camunda8ProcessService;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.springboot.adapter.ModuleAwareBpmnDeployment;
import io.vanillabp.springboot.adapter.VanillaBpProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.camunda.bpm.model.xml.impl.util.IoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;

public class Camunda8DeploymentAdapter extends ModuleAwareBpmnDeployment {

	private static final Logger logger = LoggerFactory.getLogger(Camunda8DeploymentAdapter.class);

    public static final String MODELCACHE_PREFIX = "C8_";
    public static final String VERSIONINFO_CURRENT = "current";
    public static final String ADAPTER_PACKAGE = "io.vanillabp.camunda8";
    public static final String PROPERTY_DEPLOYMENT_PRIORITY = "io.vanillabp.deployment.priority";

    public static final String PROPERTY_TASKLISTENER_PREFIXES = "io.vanillabp.tasklistener.prefixes";

    public static final Pattern PROPERTY_TASKLISTENER_PREFIXES_REGEX = Pattern.compile("io\\.vanillabp.+tasklistener\\.prefixes");

    public static final Pattern PROPERTY_EXECUTIONLISTENER_PREFIXES_REGEX = Pattern.compile("io\\.vanillabp.+executionlistener\\.prefixes");

    private final BpmnParser bpmnParser = new BpmnParser();

    private final Camunda8TaskWiring taskWiring;

    private final Camunda8VanillaBpProperties camunda8Properties;

    private final ApplicationEventPublisher applicationEventPublisher;
    
    private CamundaClient client;

    @SuppressWarnings("unchecked")
    public static void initializeCrossCuttingProperties() {
        ModuleAwareBpmnDeployment.adapterProperties.put(
                PROPERTY_TASKLISTENER_PREFIXES,
                List.of(Camunda8TaskWiring.TASKDEFINITION_USERTASK_ZEEBE));

        final var existingPriorities = (List<String>) ModuleAwareBpmnDeployment.adapterProperties
                .get(PROPERTY_DEPLOYMENT_PRIORITY);
        if (existingPriorities == null) {
            final var priorities = new LinkedList<String>();
            priorities.add(ADAPTER_PACKAGE);
            ModuleAwareBpmnDeployment.adapterProperties.put(PROPERTY_DEPLOYMENT_PRIORITY, priorities);
        } else {
            // set priority of business cockpit adapter to highest, to enforce task-listeners are added after other adapters
            existingPriorities.add(0, ADAPTER_PACKAGE);
        }
    }

    public Camunda8DeploymentAdapter(
            final String applicationName,
            final VanillaBpProperties properties,
            final Camunda8VanillaBpProperties camunda8Properties,
            final Camunda8TaskWiring taskWiring,
            final ApplicationEventPublisher applicationEventPublisher) {
        
        super(properties, applicationName);
        this.camunda8Properties = camunda8Properties;
        this.taskWiring = taskWiring;
        this.applicationEventPublisher = applicationEventPublisher;

    }

    @Override
    protected Logger getLogger() {
    	
    	return logger;
    	
    }
    
    @Override
    protected String getAdapterId() {
        
        return Camunda8AdapterConfiguration.ADAPTER_ID;
        
    }

    @EventListener
    @SuppressWarnings("unchecked")
    public void camundaClientCreated(
            final CamundaClientCreatedEvent event) {

        final var existingPriorities = (List<String>) ModuleAwareBpmnDeployment.adapterProperties
                .get(PROPERTY_DEPLOYMENT_PRIORITY);
        if (existingPriorities.isEmpty()) {
            return;
        }
        if (!existingPriorities.get(0).equals(ADAPTER_PACKAGE)) {
            return;
        }
        existingPriorities.remove(0);

        this.client = event.getClient();
        taskWiring.accept(client);

        deployAllWorkflowModules();

        // next adapter
        applicationEventPublisher.publishEvent(event);

    }

    private void examineProcessVersionTags(
            final BpmnModelInstanceImpl model,
            final BiConsumer<String, String> versionTagConsumer) {

        model
                .getModelElementsByType(Process.class)
                .stream()
                .filter(Process::isExecutable)
                .filter(process -> process.getSingleExtensionElement(ZeebeVersionTag.class) != null)
                .forEach(process -> versionTagConsumer.accept(process.getId(), process.getSingleExtensionElement(ZeebeVersionTag.class).getValue()));

    }

    @EventListener(ApplicationReadyEvent.class)
    public void deployBpmnModels() {

        synchronized (ModuleAwareBpmnDeployment.bpmnModelCache) {

            if (ModuleAwareBpmnDeployment.bpmnModelCache.isEmpty()) {
                return;
            }

            final var resourcesDeployed = new HashSet<String>();

            ModuleAwareBpmnDeployment.bpmnModelCache
                        .entrySet()
                        .stream()
                        .filter(entry -> entry.getKey().startsWith(MODELCACHE_PREFIX))
                        .map(entry -> Map.entry(entry.getKey().substring(MODELCACHE_PREFIX.length()), entry.getValue()))
                        .collect(Collectors.groupingBy(
                                entry -> entry.getValue().getKey(),  // grouping by workflow module id
                                Collectors.mapping(entry -> Map.entry(entry.getKey(), entry.getValue().getValue()), // preserve resource and model
                                        Collectors.toSet())))
                        // for each workflow module
                        .forEach((workflowModuleId, resources) -> {
                            final var tenantId = camunda8Properties.getTenantId(workflowModuleId);
                            final var deployResourceCommand = client.newDeployResourceCommand();
                            final var processVersionTags = new HashMap<String, String>();
                            // deploy all bpmns at once
                            resources
                                    .stream()
                                    .peek(resource -> {
                                        if (logger.isTraceEnabled()) {
                                            logger.trace("Generated BPMN:\n{}", IoUtil.convertXmlDocumentToString(
                                                    ((BpmnModelInstanceImpl) resource.getValue()).getDocument()));
                                        }
                                    })
                                    .map(resource -> {
                                        final var model = (BpmnModelInstanceImpl) resource.getValue();
                                        examineProcessVersionTags(model, processVersionTags::put);
                                        resourcesDeployed.add(MODELCACHE_PREFIX + resource.getKey());
                                        return deployResourceCommand.addProcessModel(model, resource.getKey());
                                    })
                                    .reduce((first, second) -> second)
                                    .map(command -> tenantId == null ? command : command.tenantId(tenantId))
                                    .map(command -> {
                                        logger.info("About to deploy BPMNs of workflow-module '{}'", workflowModuleId);
                                        return command.send().join();
                                    })
                                    .ifPresent(result -> {
                                        logger.info("Deployed {} BPMNs of workflow-module '{}'",
                                                result.getProcesses().size(), workflowModuleId);
                                        applicationEventPublisher.publishEvent(new BpmnModelCacheProcessed(
                                                this.getClass().getName(),
                                                workflowModuleId,
                                                result
                                                        .getProcesses()
                                                        .stream()
                                                        .map(process -> Map.entry(
                                                                process.getBpmnProcessId(),
                                                                processVersionTags.containsKey(process.getBpmnProcessId())
                                                                        ? "%s:%d".formatted(processVersionTags.get(process.getBpmnProcessId()), process.getVersion())
                                                                        : "%d".formatted(process.getVersion())))
                                                        .toList()));
                                    });
                        });

            resourcesDeployed.forEach(ModuleAwareBpmnDeployment.bpmnModelCache::remove);

        }

    }

    @Override
    protected void doDeployment(
    		final String workflowModuleId,
            final Resource[] bpmns,
            final Resource[] dmns,
            final Resource[] cmms) throws Exception {

        final var tenantId = camunda8Properties.getTenantId(workflowModuleId);
        final var cachableWorkflowModuleId = workflowModuleId == null ? "default" : workflowModuleId;

        // Add all DMNs to deploy-command: on one hand to deploy them and on the
        // other hand to consider their hash code on calculating total package hash code
        final var deployResourceCommand = client.newDeployResourceCommand();
        Arrays
                .stream(dmns)
                .map(resource -> {
                    try (final var dmn = resource.getInputStream()) {
                        return deployResourceCommand.addResourceStream(dmn, resource.getFilename());
                    } catch (IOException e) {
                        throw new RuntimeException(
                                "Could not load DMN '"
                                + resource.getFilename()
                                + "'!", e);
                    }
                })
                .reduce((first, second) -> second)
                .map(command -> tenantId == null ? command : command.tenantId(tenantId))
                .map(command -> {
                    logger.info("About to deploy DMNs of workflow-module '{}'", workflowModuleId);
                    return command.send().join();
                })
                .ifPresent(result -> logger.info("Deployed {} DMNs of workflow-module '{}'",
                        result.getDecisions().size(), workflowModuleId));

        // Add all BPMNs to model cache: on one hand to deploy them and on the
        // other hand to wire them to the using project beans according to the SPI
        Arrays
                .stream(bpmns)
                .forEach(resource -> {
                    try (var inputStream = resource.getInputStream()) {
                        final var filename = resource.getFilename();
                        logger.info("About to process '{}' of workflow-module '{}'",
                                filename,
                                cachableWorkflowModuleId);
                    	Optional
                                .ofNullable(ModuleAwareBpmnDeployment.bpmnModelCache.get(MODELCACHE_PREFIX + filename))
                                .or(() -> {
                                    final var uncachedModel = bpmnParser.parseModelFromStream(inputStream);
                                    final var entry = Map.<String, Object>entry(cachableWorkflowModuleId, uncachedModel);
                                    ModuleAwareBpmnDeployment.bpmnModelCache.put(MODELCACHE_PREFIX + filename, entry);
                                    return Optional.of(entry);
                                })
                                .map(Map.Entry::getValue)
                                .ifPresent(model -> processBpmnModel(
                                        workflowModuleId, VERSIONINFO_CURRENT, (BpmnModelInstanceImpl) model, false));

                    } catch (IOException e) {
                        throw new RuntimeException(e.getMessage());
                    }
                });

        // BPMNs which were deployed in the past need to be forced to be parsed for wiring
        int currentPage = 0;
        while (currentPage != -1) {
            final var finalPage = currentPage;
            var request = client
                    .newProcessDefinitionSearchRequest()
                    .sort(sort -> sort.name().version())
                    .page(page -> page.from(finalPage).limit(1));
            if (tenantId != null) {
                request = request.filter(filter -> filter.tenantId(tenantId));
            }
            var processDefinitions = request.send().join();
            currentPage = processDefinitions.page().hasMoreTotalItems() ? currentPage + 1 : -1;

            processDefinitions
                    .items()
                    .stream()
                    .map(processDefinition -> Map.entry(processDefinition, client.newProcessDefinitionGetXmlRequest(processDefinition.getProcessDefinitionKey())))
                    .map(bpmnRequest -> Map.entry(bpmnRequest.getKey(), bpmnRequest.getValue().send().join()))
                    .map(bpmn -> Map.entry(bpmn.getKey(), new ByteArrayInputStream(bpmn.getValue().getBytes(StandardCharsets.UTF_8))))
                    .map(bpmnStream -> Map.entry(bpmnStream.getKey(), bpmnParser.parseModelFromStream(bpmnStream.getValue())))
                    .map(bpmnModel -> {
                        final var versionTag = bpmnModel.getKey().getVersionTag();
                        final String versionInfo;
                        if (versionTag != null) {
                            versionInfo = "%s:%d".formatted(versionTag, bpmnModel.getKey().getVersion());
                        } else {
                            versionInfo = "%d".formatted(bpmnModel.getKey().getVersion());
                        }
                        return Map.entry(versionInfo, bpmnModel.getValue());
                    })
                    .forEach(model -> processBpmnModel(
                            workflowModuleId,
                            model.getKey(),
                            model.getValue(), true));
        }

    }
    
    private void processBpmnModel(
            final String workflowModuleId,
            final String versionInfo,
    		final BpmnModelInstanceImpl model,
    		final boolean oldVersionBpmn) {

        final var processService = new Camunda8ProcessService[] { null };

        model.getModelElementsByType(Process.class)
                .stream()
                .filter(Process::isExecutable)
                // wire service port
                .peek(process -> {
                    final var startEvents = model
                            .getModelElementsByType(StartEvent.class);
                    final var messageBasedStartEventsMessageNames = startEvents
                            .stream()
                            .filter(startEvent -> startEvent.getParentElement().equals(process))
                            .map(startEvent -> startEvent.getChildElementsByType(MessageEventDefinition.class))
                            .filter(eventDefinitions -> !eventDefinitions.isEmpty())
                            .map(eventDefinitions -> eventDefinitions.iterator().next().getMessage().getName())
                            .collect(Collectors.toList());
                    final var signalBasedStartEventsSignalNames = startEvents
                            .stream()
                            .filter(startEvent -> startEvent.getParentElement().equals(process))
                            .map(startEvent -> startEvent.getChildElementsByType(SignalEventDefinition.class))
                            .filter(eventDefinitions -> !eventDefinitions.isEmpty())
                            .map(eventDefinitions -> eventDefinitions.iterator().next().getSignal().getName())
                            .collect(Collectors.toList());
                    processService[0] = taskWiring.wireService(
                            workflowModuleId,
                            process.getId(),
                            oldVersionBpmn ? null : messageBasedStartEventsMessageNames,
                            oldVersionBpmn ? null : signalBasedStartEventsSignalNames);
                })
                // wire task methods
                .flatMap(process ->
                        {
                            boolean allowConnectors = camunda8Properties.areConnectorsAllowed(workflowModuleId, process.getId());
                            return Stream.of(
                                taskWiring.connectablesForType(workflowModuleId, ModuleAwareBpmnDeployment.adapterProperties,
                                        process, versionInfo, model, ServiceTask.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(workflowModuleId, ModuleAwareBpmnDeployment.adapterProperties,
                                        process, versionInfo, model, BusinessRuleTask.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(workflowModuleId, ModuleAwareBpmnDeployment.adapterProperties,
                                        process, versionInfo, model, SendTask.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(workflowModuleId, ModuleAwareBpmnDeployment.adapterProperties,
                                        process, versionInfo, model, UserTask.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(workflowModuleId, ModuleAwareBpmnDeployment.adapterProperties,
                                        process, versionInfo, model, IntermediateThrowEvent.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(workflowModuleId, ModuleAwareBpmnDeployment.adapterProperties,
                                        process, versionInfo, model, EndEvent.class, allowConnectors, !oldVersionBpmn)
                            )
                            .flatMap(i -> i);
                        } // map stream of streams to one stream
                    )
                .forEach(connectable -> taskWiring.wireTask(workflowModuleId, processService[0], connectable));
    	
    }

    /**
     * Only necessary for Spring Boot tests / @CamundaSpringProcessTest.
     * After one test has completed, we need to reinitialize the deployment priority list.
     */
    public void doCleanup() {
        initializeCrossCuttingProperties();
    }
}
