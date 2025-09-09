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
import io.vanillabp.camunda8.utils.HashCodeInputStream;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.springboot.adapter.ModuleAwareBpmnDeployment;
import io.vanillabp.springboot.adapter.VanillaBpProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

public class Camunda8DeploymentAdapter extends ModuleAwareBpmnDeployment {

	private static final Logger logger = LoggerFactory.getLogger(Camunda8DeploymentAdapter.class);
	
	private final BpmnParser bpmnParser = new BpmnParser();

    private final Camunda8TaskWiring taskWiring;

    private final DeploymentService deploymentService;

    private final Camunda8VanillaBpProperties camunda8Properties;
    
    private CamundaClient client;

    public Camunda8DeploymentAdapter(
            final String applicationName,
            final VanillaBpProperties properties,
            final Camunda8VanillaBpProperties camunda8Properties,
            final DeploymentService deploymentService,
            final Camunda8TaskWiring taskWiring) {
        
        super(properties, applicationName);
        this.camunda8Properties = camunda8Properties;
        this.taskWiring = taskWiring;
        this.deploymentService = deploymentService;

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

    @Override
    protected void doDeployment(
    		final String workflowModuleId,
            final Resource[] bpmns,
            final Resource[] dmns,
            final Resource[] cmms) throws Exception {

        final var deploymentHashCode = new int[] { 0 };

        final var deployResourceCommand = client.newDeployResourceCommand();

        // Add all DMNs to deploy-command: on one hand to deploy them and on the
        // other hand to consider their hash code on calculating total package hash code
        Arrays
                .stream(dmns)
                .forEach(resource -> {
                    try (var inputStream = new HashCodeInputStream(
                            resource.getInputStream(),
                            deploymentHashCode[0])) {
                        
                        final var bytes = StreamUtils.copyToByteArray(inputStream);
                        
                        deploymentHashCode[0] = inputStream.getTotalHashCode();
                        
                        deployResourceCommand.addResourceBytes(bytes, resource.getFilename());
                        
                    } catch (IOException e) {
                        throw new RuntimeException(e.getMessage());
                    }
                });
        
        final var deployedProcesses = new HashMap<String, DeployedBpmn>();

        final boolean[] hasDeployables = { false };

        // Add all BPMNs to deploy-command: on one hand to deploy them and on the
        // other hand to wire them to the using project beans according to the SPI
        final var deploymentCommand = Arrays
                .stream(bpmns)
                .map(resource -> {
                    try (var inputStream = new HashCodeInputStream(
                            resource.getInputStream(),
                            deploymentHashCode[0])) {
                        
                        logger.info("About to deploy '{}' of workflow-module '{}'",
                                resource.getFilename(),
                                workflowModuleId == null ? "default" : workflowModuleId);
                    	final var model = bpmnParser.parseModelFromStream(inputStream);

                    	final var bpmn = deploymentService.addBpmn(
                                model,
                                inputStream.hashCode(),
                                resource.getDescription());

                        processBpmnModel(workflowModuleId, deployedProcesses, bpmn, model, false);
                        deploymentHashCode[0] = inputStream.getTotalHashCode();

                        hasDeployables[0] = true;

                    	return deployResourceCommand.addProcessModel(model, resource.getFilename());
                    	
                    } catch (IOException e) {
                        throw new RuntimeException(e.getMessage());
                    }
                })
                .filter(Objects::nonNull)
                .reduce((first, second) -> second);
        
        if (hasDeployables[0]) {

            final var tenantId = camunda8Properties.getTenantId(workflowModuleId);
            final var deployedResources = deploymentCommand
                    .map(command -> tenantId == null ? command : command.tenantId(tenantId))
                    .map(command -> command
                            .send()
                            .join())
                    .orElseThrow();

            // BPMNs which are part of the current package will stored
            deployedResources
                    .getProcesses()
                    .forEach(process -> deploymentService.addProcess(
                            workflowModuleId,
                            deploymentHashCode[0],
                            process,
                            deployedProcesses.get(process.getBpmnProcessId())));
            
        }
        
        // BPMNs which were deployed in the past need to be forced to be parsed for wiring
        deploymentService
                .getBpmnNotOfPackage(workflowModuleId, deploymentHashCode[0])
                .forEach(bpmn -> {
                    
                    try (var inputStream = new ByteArrayInputStream(
                            bpmn.getResource())) {
                        
                        logger.info("About to verify old BPMN '{}' of workflow-module '{}'",
                                bpmn.getResourceName(),
                                workflowModuleId == null ? "default" : workflowModuleId);
                        final var model = bpmnParser.parseModelFromStream(inputStream);
        
                        processBpmnModel(workflowModuleId, deployedProcesses, bpmn, model, true);
                        
                    } catch (IOException e) {
                        throw new RuntimeException(e.getMessage());
                    }
                    
                });
        
    }
    
    private void processBpmnModel(
            final String workflowModuleId,
            final Map<String, DeployedBpmn> deployedProcesses,
            final DeployedBpmn bpmn,
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
                    deployedProcesses.put(process.getId(), bpmn);
                })
                // wire task methods
                .flatMap(process ->
                        {
                            boolean allowConnectors = camunda8Properties.areConnectorsAllowed(workflowModuleId, process.getId());
                            return Stream.of(
                                //taskWiring.connectablesForType(process, model, ZeebeExecutionListener.class, allowConnectors),
                                taskWiring.connectablesForType(process, model, ServiceTask.class, allowConnectors),
                                taskWiring.connectablesForType(process, model, BusinessRuleTask.class, allowConnectors),
                                taskWiring.connectablesForType(process, model, SendTask.class, allowConnectors),
                                //taskWiring.connectablesForType(process, model, ZeebeTaskListener.class, allowConnectors),
                                taskWiring.connectablesForType(process, model, UserTask.class, allowConnectors),
                                taskWiring.connectablesForType(process, model, IntermediateThrowEvent.class, allowConnectors),
                                taskWiring.connectablesForType(process, model, EndEvent.class, allowConnectors)
                            )
                            .flatMap(i -> i);
                        } // map stream of streams to one stream
                    )
                .forEach(connectable -> taskWiring.wireTask(workflowModuleId, processService[0], connectable));
    	
    }
    
}
