package io.vanillabp.camunda8.springboot.processservice;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

import io.vanillabp.camunda8.springboot.Camunda8AdapterBeanRegistrar;

/**
 * Wires the Camunda 8 adapter's process-service and deployment-service beans: ONE
 * element bean per configured adapter id of type {@code camunda8}, registered by the
 * imported {@link Camunda8AdapterBeanRegistrar} (multiple ids of one BPMS type = the
 * migration scenario, e.g. an on-prem and a SaaS cluster side by side).
 */
@AutoConfiguration
@Import(Camunda8AdapterBeanRegistrar.class)
public class Camunda8AdapterProcessServiceConfiguration {

  /**
   * Built by Spring Boot while it reads the auto-configuration imports.
   */
  public Camunda8AdapterProcessServiceConfiguration() {
  }

}
