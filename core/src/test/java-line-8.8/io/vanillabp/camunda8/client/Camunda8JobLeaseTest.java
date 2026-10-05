package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.command.CompleteJobCommandStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * This line has no lease, and the adapter says so rather than pretending.
 * <p>
 * The point of asserting it per line is the configuration key: an application which moves
 * between lines carries one configuration, so the key is accepted here, changes nothing, and
 * the boot writes one line about it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8JobLeaseTest {

  @Test
  @DisplayName("This line cannot lease")
  public void thisLineCannotLease() {

    assertFalse(Camunda8JobLease.supportedByThisLine());

  }

  @Test
  @DisplayName("Nothing is asked of the client, and no answer carries a token")
  public void nothingReachesTheClient() {

    final var builder = mock(JobWorkerBuilderStep1.JobWorkerBuilderStep3.class, RETURNS_SELF);
    assertSame(builder, Camunda8JobLease.leaseTheActivations(builder));
    assertTrue(
        mockingDetails(builder).getInvocations().isEmpty(),
        "the worker is opened exactly as it was before");

    assertNull(Camunda8JobLease.tokenOf(mock(ActivatedJob.class)));

    final var completion = mock(CompleteJobCommandStep1.class, RETURNS_SELF);
    assertSame(completion, Camunda8JobLease.withToken(completion, "a-token-from-somewhere"));
    assertTrue(mockingDetails(completion).getInvocations().isEmpty());

  }

  @Test
  @DisplayName("A configured adapter boots without the key, and with it gets one line")
  public void theKeyIsAcceptedAndIgnored() {

    final var lines = new ArrayList<String>();
    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");

    configuration.validateJobLease("c8", lines::add);
    assertTrue(lines.isEmpty(), "a line which cannot lease asks nothing of the application");

    configuration.setJobLease(Camunda8AdapterConfiguration.JobLease.USE);
    configuration.validateJobLease("c8", lines::add);
    assertSame(1, lines.size());
    assertTrue(lines.getFirst().contains("vanillabp.adapters.c8.job-lease"), lines.toString());
    assertTrue(lines.getFirst().contains("no effect on this release line"), lines.toString());
    assertFalse(configuration.leasesItsJobs(), "and nothing is leased whatever is written");

  }

}
