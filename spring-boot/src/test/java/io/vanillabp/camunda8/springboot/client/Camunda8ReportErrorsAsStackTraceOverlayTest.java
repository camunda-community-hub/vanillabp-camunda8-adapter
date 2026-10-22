package io.vanillabp.camunda8.springboot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * <code>report-errors-as-stack-trace</code> is read at the four levels every scope-specific key
 * of this adapter has. The levels below switch it on and off in turn. So each answer shows that
 * its own level won, in both directions. The binding is Spring's own, without a context, so no
 * application has to start.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ReportErrorsAsStackTraceOverlayTest {

  private static VanillaBpCamunda8Properties overlay(
      final Map<String, String> properties) {

    return new Binder(new MapConfigurationPropertySource(properties))
        .bind("vanillabp", VanillaBpCamunda8Properties.class)
        .get();

  }

  @Test
  @DisplayName("report-errors-as-stack-trace resolves through all four levels, the most specific one wins")
  public void theMostSpecificLevelWins() {

    final var overlay = overlay(
        Map.of(
            "vanillabp.adapters.c8.report-errors-as-stack-trace", "true",
            "vanillabp.workflow-modules.test-app.adapters.c8.report-errors-as-stack-trace", "false",
            "vanillabp.workflow-modules.test-app.workflows.TaskProcess.adapters.c8.report-errors-as-stack-trace",
            "true",
            "vanillabp.workflow-modules.test-app.workflows.TaskProcess.tasks.happyTask.adapters.c8.report-errors-as-stack-trace",
            "false"));

    assertEquals(Boolean.FALSE, overlay.reportErrorsAsStackTraceFor("test-app", "TaskProcess", "happyTask", "c8"));
    assertEquals(Boolean.TRUE, overlay.reportErrorsAsStackTraceFor("test-app", "TaskProcess", "otherTask", "c8"));
    assertEquals(Boolean.FALSE, overlay.reportErrorsAsStackTraceFor("test-app", "OtherProcess", "someTask", "c8"));
    assertEquals(Boolean.TRUE, overlay.reportErrorsAsStackTraceFor("other-module", "SomeProcess", "someTask", "c8"));
    // a job which serves no task, like the end of a workflow, reads the workflow level
    assertEquals(Boolean.TRUE, overlay.reportErrorsAsStackTraceFor("test-app", "TaskProcess", null, "c8"));
    // an adapter id which configures nothing leaves it to the default
    assertNull(overlay.reportErrorsAsStackTraceFor("test-app", "TaskProcess", "happyTask", "other-adapter"));

  }

  @Test
  @DisplayName("The place version 1 read the key, the workflow module, still works on its own")
  public void theLevelOfVersionOneStillWorks() {

    // exactly what a version 1 configuration says, under the adapter id 'camunda8' it used
    final var overlay = overlay(
        Map.of("vanillabp.workflow-modules.Demo.adapters.camunda8.report-errors-as-stack-trace", "true"));

    assertEquals(Boolean.TRUE, overlay.reportErrorsAsStackTraceFor("Demo", "AnyProcess", "anyTask", "camunda8"));
    assertNull(overlay.reportErrorsAsStackTraceFor("OtherModule", "AnyProcess", "anyTask", "camunda8"));

  }

}
