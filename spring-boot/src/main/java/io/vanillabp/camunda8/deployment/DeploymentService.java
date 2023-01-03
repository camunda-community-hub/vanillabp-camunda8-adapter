package io.vanillabp.camunda8.deployment;

import io.camunda.zeebe.client.api.response.Process;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.impl.BpmnParser;
import io.vanillabp.springboot.adapter.SpringDataUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeploymentService {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentService.class);

    private final BpmnParser bpmnParser = new BpmnParser();

    private final SpringDataUtil springDataUtil;
    
    private final DeployedProcessRepository deployedProcessRepository;

    private final DeploymentRepository deploymentRepository;
    
    private final DeploymentResourceRepository deploymentResourceRepository;

    private final Map<Long, io.camunda.zeebe.model.bpmn.instance.Process> cachedProcesses = new HashMap<>();

    public DeploymentService(
            final SpringDataUtil springDataUtil,
            final DeploymentRepository deploymentRepository,
            final DeploymentResourceRepository deploymentResourceRepository,
            final DeployedProcessRepository deployedProcessRepository) {

        this.springDataUtil = springDataUtil;
        this.deploymentRepository = deploymentRepository;
        this.deploymentResourceRepository = deploymentResourceRepository;
        this.deployedProcessRepository = deployedProcessRepository;
        
    }
    
    public DeployedBpmn addBpmn(
            final BpmnModelInstance model,
            final int fileId,
            final String resourceName) {
        
        final var previous = deploymentResourceRepository.findById(fileId);
        if (previous.isPresent()) {
            return (DeployedBpmn) previous.get();
        }

        final var outStream = new ByteArrayOutputStream();
        Bpmn.writeModelToStream(outStream, model);
        
        final var bpmn = new DeployedBpmn();
        bpmn.setFileId(fileId);
        bpmn.setResource(outStream.toByteArray());
        bpmn.setResourceName(resourceName);
        
        return deploymentResourceRepository.save(bpmn);
        
    }
    
    public DeployedProcess addProcess(
            final int packageId,
            final Process camunda8DeployedProcess,
            final DeployedBpmn bpmn) {
        
        final var versionedId = camunda8DeployedProcess.getProcessDefinitionKey();
        
        final var previous = deploymentRepository.findById(versionedId);
        if ((previous.isPresent())
                && (previous.get().getPackageId() == packageId)) {
            return (DeployedProcess) previous.get();
        }

        final var deployedProcess = new DeployedProcess();
        
        deployedProcess.setDefinitionKey(versionedId);
        deployedProcess.setVersion(camunda8DeployedProcess.getVersion());
        deployedProcess.setPackageId(packageId);
        deployedProcess.setBpmnProcessId(camunda8DeployedProcess.getBpmnProcessId());
        deployedProcess.setDeployedResource(bpmn);
        deployedProcess.setPublishedAt(OffsetDateTime.now());
        
        return deploymentRepository.save(deployedProcess);
        
    }

    public List<DeployedBpmn> getBpmnNotOfPackage(final int packageId) {

        return deployedProcessRepository.findDistinctDeployedResourceByPackageIdNot(packageId);

    }

    public io.camunda.zeebe.model.bpmn.instance.Process getProcess(
            final long processDefinitionKey) {
        
        synchronized (cachedProcesses) {
            
            final var cached = cachedProcesses.get(processDefinitionKey);
            if (cached != null) {
                return cached;
            }
           
            final var deployedProcess = (DeployedProcess) springDataUtil
                    .unproxy(deploymentRepository
                            .findById(processDefinitionKey)
                            .orElseThrow());
            final var deployedResource = deployedProcess.getDeployedResource();
            
            try (final var inputStream = new ByteArrayInputStream(
                    deployedResource.getResource())) {

                bpmnParser
                        .parseModelFromStream(inputStream)
                        .getModelElementsByType(io.camunda.zeebe.model.bpmn.instance.Process.class)
                        .stream()
                        .filter(io.camunda.zeebe.model.bpmn.instance.Process::isExecutable)
                        .map(process -> {
                            final var key = deployedResource
                                    .getDeployments()
                                    .stream()
                                    .filter(deployment -> ((DeployedProcess) deployment)
                                            .getBpmnProcessId()
                                            .equals(process.getId()))
                                    .findFirst()
                                    .get()
                                    .getDefinitionKey();
                            return Map.entry(key, process);
                        })
                        .forEach(entry -> cachedProcesses.put(entry.getKey(), entry.getValue()));

            } catch (Exception e) {
                logger.warn("Could not parse stored BPMN resource '{}'!", deployedResource.getResourceName());
            }
            
            return cachedProcesses.get(processDefinitionKey);

        }
        
    }

}
