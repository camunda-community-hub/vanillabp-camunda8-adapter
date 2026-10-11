package io.vanillabp.camunda8.springboot.processservice;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

import io.vanillabp.camunda8.springboot.Camunda8AdapterBeanRegistrar;

/**
 * Wires the process service and the deployment service of the Camunda 8 adapter. There is
 * ONE bean of each per configured adapter id of type {@code camunda8}. The imported
 * {@link Camunda8AdapterBeanRegistrar} registers them. Several ids of one BPMS type are the
 * setup for a migration, for example an on-prem cluster and a SaaS cluster side by side.
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
