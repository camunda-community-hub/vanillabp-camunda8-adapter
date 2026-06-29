package io.vanillabp.camunda8.service.bpmn;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.zeebe.model.bpmn.impl.BpmnModelInstanceImpl;
import io.camunda.zeebe.model.bpmn.impl.BpmnParser;
import io.camunda.zeebe.model.bpmn.instance.CallActivity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

public class ProcessDefinitionCollector {

    private static final String ZEEBE_NS = "http://camunda.org/schema/zeebe/1.0";

    private final BpmnParser bpmnParser = new BpmnParser();

    private final CamundaClient camundaClient;

    public ProcessDefinitionCollector(
            final CamundaClient camundaClient) {

        this.camundaClient = camundaClient;

    }

    public List<io.vanillabp.spi.process.ProcessDefinition> collectAllDefinitions(
            final ProcessInstance processInstance,
            final String tenantId) {

        final var parentDef = loadProcessDefinition(
                processInstance.getProcessDefinitionKey(), tenantId);

        final var defToElements = new LinkedHashMap<String, List<String>>();
        defToElements.put(String.valueOf(parentDef.getProcessDefinitionKey()), null);

        final var bpmnXml = loadBpmnXml(parentDef.getProcessDefinitionKey());
        final var model = parseBpmnModel(bpmnXml);

        for (final var callActivity : findCallActivities(model)) {

            final var calledElement = callActivity.getCalledElement();
            if (calledElement == null || calledElement.isBlank()) {
                continue;
            }

            var resolvedDef = resolveCalledDefinition(
                    callActivity, model, parentDef, tenantId);
            if (resolvedDef != null) {
                String defKey = String.valueOf(resolvedDef.getProcessDefinitionKey());
                defToElements
                        .computeIfAbsent(defKey, k -> new ArrayList<>())
                        .add(callActivity.getId());
            }
        }

        final var result = new ArrayList<io.vanillabp.spi.process.ProcessDefinition>();
        for (var entry : defToElements.entrySet()) {
            var def = loadProcessDefinition(Long.parseLong(entry.getKey()), tenantId);
            result.add(toProcessDefinition(def, entry.getValue()));
        }
        return result;

    }

    private ProcessDefinition loadProcessDefinition(
            final long processDefinitionKey,
            final String tenantId) {

        final var result = camundaClient.newProcessDefinitionSearchRequest()
                .filter(filter -> filter
                        .processDefinitionKey(processDefinitionKey)
                        .tenantId(tenantId))
                .send()
                .join();

        return result
                .items()
                .stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Process definition not found: " + processDefinitionKey));

    }

    private ProcessDefinition resolveCalledDefinition(
            final CallActivity callActivity,
            final BpmnModelInstanceImpl model,
            final ProcessDefinition parentDef,
            final String tenantId) {

        final var definitions = camundaClient
                .newProcessDefinitionSearchRequest()
                .filter(filter -> filter
                        .processDefinitionId(callActivity.getCalledElement())
                        .tenantId(tenantId))
                .send()
                .join()
                .items();
        if (definitions.isEmpty()) {
            return null;
        }

        final var bindingType = getBindingType(callActivity);
        if ("deployment".equals(bindingType)) {

            // TODO Implement once C8 API provides deployment information

        } else if ("versionTag".equals(bindingType)) {
            String versionTag = getVersionTagAttribute(callActivity, model);
            if (versionTag != null) {
                return definitions
                        .stream()
                        .filter(d -> versionTag.equals(d.getVersionTag()))
                        .max(Comparator.comparing(io.camunda.client.api.search.response.ProcessDefinition::getVersion))
                        .orElse(null);
            }
        }

        // Default: latest
        return definitions.stream()
                .max(Comparator.comparing(ProcessDefinition::getVersion))
                .orElse(null);
    }

    public String loadBpmnXml(
            final long processDefinitionKey) {

        return camundaClient
                .newProcessDefinitionGetXmlRequest(processDefinitionKey)
                .send()
                .join();

    }

    private BpmnModelInstanceImpl parseBpmnModel(
            final String bpmnXml) {

        return bpmnParser.parseModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

    }

    private Collection<CallActivity> findCallActivities(
            final BpmnModelInstanceImpl model) {

        return model.getModelElementsByType(
                CallActivity.class);

    }

    private String getBindingType(
            final CallActivity ca) {

        return Optional
                .ofNullable(ca.getExtensionElements())
                .stream()
                .flatMap(extensionElements -> extensionElements.getElements().stream())
                .filter(element -> "calledElement".equals(element.getElementType().getTypeName())
                        && ZEEBE_NS.equals(element.getElementType().getTypeNamespace()))
                .findFirst()
                .map(element -> element.getAttributeValue("bindingType"))
                .orElse(null);

    }

    private String getVersionTagAttribute(
            final CallActivity ca,
            final BpmnModelInstanceImpl model) {

        var extensionElements = ca.getExtensionElements();
        if (extensionElements != null) {
            for (var element : extensionElements.getElements()) {
                if ("calledElement".equals(element.getElementType().getTypeName())
                        && ZEEBE_NS.equals(element.getElementType().getTypeNamespace())) {
                    return element.getAttributeValue("versionTag");
                }
            }
        }
        return null;
    }

    private io.vanillabp.spi.process.ProcessDefinition toProcessDefinition(ProcessDefinition def,
                                                  List<String> usedByElements) {
        return new io.vanillabp.spi.process.ProcessDefinition(
                String.valueOf(def.getProcessDefinitionKey()),
                def.getProcessDefinitionId(),
                def.getVersionTag() != null
                        ? "%s:%d".formatted(def.getVersionTag(), def.getVersion())
                        : String.valueOf(def.getVersion()),
                usedByElements
        );
    }

}
