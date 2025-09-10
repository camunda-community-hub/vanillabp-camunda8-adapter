package io.vanillabp.camunda8.deployment;

import io.camunda.client.CamundaClient;
import io.camunda.spring.client.event.CamundaClientCreatedEvent;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.camunda.bpm.model.xml.impl.util.IoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;

public class Camunda8DeploymentAdapter extends ModuleAwareBpmnDeployment {

	private static final Logger logger = LoggerFactory.getLogger(Camunda8DeploymentAdapter.class);
	
	private final BpmnParser bpmnParser = new BpmnParser();

    private final Camunda8TaskWiring taskWiring;

    private final Camunda8VanillaBpProperties camunda8Properties;
    
    private CamundaClient client;

    public Camunda8DeploymentAdapter(
            final String applicationName,
            final VanillaBpProperties properties,
            final Camunda8VanillaBpProperties camunda8Properties,
            final Camunda8TaskWiring taskWiring) {
        
        super(properties, applicationName);
        this.camunda8Properties = camunda8Properties;
        this.taskWiring = taskWiring;

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
    public void camundaClientCreated(
            final CamundaClientCreatedEvent event) {

        this.client = event.getClient();

        deployAllWorkflowModules();

        taskWiring.openWorkers();

    }

    @EventListener(ApplicationReadyEvent.class)
    public void deployBpmnModels() {

        if (ModuleAwareBpmnDeployment.bpmnModelCache.isEmpty()) {
            return;
        }

        ModuleAwareBpmnDeployment.bpmnModelCache
                .entrySet()
                .stream()
                .collect(Collectors.groupingBy(
                        entry -> entry.getValue().getKey(),  // grouping by workflow module id
                        Collectors.mapping(entry -> Map.entry(entry.getKey(), entry.getValue().getValue()), // preserve resource and model
                        Collectors.toSet())))
                // for each workflow module
                .forEach((workflowModuleId, resources) -> {
                    final var tenantId = camunda8Properties.getTenantId(workflowModuleId);
                    final var deployResourceCommand = client.newDeployResourceCommand();
                    // deploy all bpmns at once
                    resources
                            .stream()
                            .peek(resource -> {
                                if (logger.isTraceEnabled()) {
                                    logger.warn("Generated BPMN:\n{}", IoUtil.convertXmlDocumentToString(
                                            ((BpmnModelInstanceImpl) resource.getValue()).getDocument()));
                                }
                            })
                            .map(resource -> deployResourceCommand.addProcessModel(
                                    (BpmnModelInstanceImpl) resource.getValue(), resource.getKey()))
                            .reduce((first, second) -> second)
                            .map(command -> tenantId == null ? command : command.tenantId(tenantId))
                            .map(command -> {
                                logger.info("About to deploy BPMNs of workflow-module '{}'", workflowModuleId);
                                return command.send().join();
                            })
                            .ifPresent(result -> logger.info("Deployed {} BPMNs of workflow-module '{}'",
                                    result.getProcesses().size(), workflowModuleId));
                });

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

        // Add all BPMNs to deploy-command: on one hand to deploy them and on the
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
                                .ofNullable(ModuleAwareBpmnDeployment.bpmnModelCache.get(filename))
                                .or(() -> {
                                    final var uncachedModel = bpmnParser.parseModelFromStream(inputStream);
                                    final var entry = Map.<String, Object>entry(cachableWorkflowModuleId, uncachedModel);
                                    ModuleAwareBpmnDeployment.bpmnModelCache.put(filename, entry);
                                    return Optional.of(entry);
                                })
                                .map(Map.Entry::getValue)
                                .ifPresent(model -> processBpmnModel(
                                        workflowModuleId, "current", (BpmnModelInstanceImpl) model, false));

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
                            versionInfo = "%s (%d)".formatted(versionTag, bpmnModel.getKey().getVersion());
                        } else {
                            versionInfo = "%s".formatted(bpmnModel.getKey().getVersion());
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

        taskWiring.accept(client);

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
                                taskWiring.connectablesForType(process, versionInfo, model, ServiceTask.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(process, versionInfo, model, BusinessRuleTask.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(process, versionInfo, model, SendTask.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(process, versionInfo, model, UserTask.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(process, versionInfo, model, IntermediateThrowEvent.class, allowConnectors, !oldVersionBpmn),
                                taskWiring.connectablesForType(process, versionInfo, model, EndEvent.class, allowConnectors, !oldVersionBpmn)
                            )
                            .flatMap(i -> i);
                        } // map stream of streams to one stream
                    )
                .forEach(connectable -> taskWiring.wireTask(workflowModuleId, processService[0], connectable));
    	
    }
    
}
