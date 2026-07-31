package io.vanillabp.camunda8.config;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/**
 * Disable all of Camunda autoconfiguration regarding {@link io.camunda.client.annotation.JobWorker}
 * and {@link io.camunda.client.annotation.Deployment} processing.
 *
 * @deprecated This filter no longer has any effect and is kept only until it is removed.
 *             <p>
 *             It used to target a Camunda auto-configuration class, which an
 *             {@code AutoConfigurationImportFilter} can suppress. A larger Camunda upgrade moved that
 *             processing into {@code AnnotationProcessorConfiguration}, and since then the filter is
 *             ineffective: that class is not an auto-configuration candidate but reached through a plain
 *             {@code @Import}, and a filter can only remove candidates. The registration chain is
 *             <pre>
 *             .imports -&gt; CamundaAutoConfiguration
 *               -@ImportAutoConfiguration-&gt; CamundaClientAllAutoConfiguration   (candidate, filterable)
 *                    -@Import-&gt; AnnotationProcessorConfiguration                (not filterable)
 *             </pre>
 *             Verified against {@code camunda-spring-boot-starter:8.9.13} and, identically, against the
 *             previously used {@code camunda-spring-boot-3-starter:8.9.11} - so this is not a Spring
 *             Boot 4 regression.
 *             <p>
 *             No problems have been observed in the meantime, which is consistent with the analysis: the
 *             beans in that configuration only act on Camunda's own annotations, while VanillaBP uses
 *             {@code @WorkflowTask} and deploys through {@code Camunda8DeploymentAdapter}.
 *             <p>
 *             Should Camunda's annotation processing ever need suppressing for real, the intended switch
 *             is a property of the vendor - {@code camunda.client.deployment.enabled} and
 *             {@code camunda.client.cluster-variables.enabled} - rather than a filter, because a property
 *             does not silently stop matching when the vendor restructures.
 *             <p>
 *             See {@code io.vanillabp.camunda8.config.ImportFilterTakesEffectTest}, which pins the
 *             measured behaviour down.
 */
@Deprecated(forRemoval = true)
public class DisableCamundaSpringAutoConfigurationImportFilter implements AutoConfigurationImportFilter {

    private static final List<String> SHOULD_SKIP = List.of(
            "io.camunda.client.spring.configuration.AnnotationProcessorConfiguration");

    @Override
    public boolean[] match(String[] classNames, AutoConfigurationMetadata metadata) {
        boolean[] matches = new boolean[classNames.length];

        for (int i = 0; i < classNames.length; i++) {
            matches[i] = classNames[i] == null || !SHOULD_SKIP.contains(classNames[i]);
        }
        return matches;
    }

}
