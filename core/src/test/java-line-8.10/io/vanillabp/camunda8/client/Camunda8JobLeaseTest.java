package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.command.CompleteJobCommandStep1;
import io.camunda.client.api.command.FailJobCommandStep1;
import io.camunda.client.api.command.ThrowErrorCommandStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a lease is on the 8.10 line, which is the first one whose client has one.
 * <p>
 * The line-specific half is small on purpose: whether the client can lease at all, how a
 * worker asks for it, and how the token of an activation reaches the three commands which
 * need it. What the adapter does with the answers is the same everywhere and is tested
 * where it is decided.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8JobLeaseTest {

  private static final String TOKEN = "0b52d6cf-23e7-4761-9dfd-04f409f8507f";

  @Test
  @DisplayName("This line can lease")
  public void thisLineCanLease() {

    assertTrue(Camunda8JobLease.supportedByThisLine());

  }

  @Test
  @DisplayName("A worker which leases asks the cluster for it")
  public void aWorkerAsksForTheLease() {

    final var builder = mock(JobWorkerBuilderStep1.JobWorkerBuilderStep3.class, RETURNS_SELF);

    assertSame(builder, Camunda8JobLease.leaseTheActivations(builder));

    verify(builder).withLease(true);

  }

  @Test
  @DisplayName("The token of an activation is what the job carries")
  public void theTokenComesFromTheJob() {

    final var job = mock(ActivatedJob.class);
    org.mockito.Mockito.when(job.getJobLeaseToken()).thenReturn(TOKEN);

    assertEquals(TOKEN, Camunda8JobLease.tokenOf(job));

  }

  @Test
  @DisplayName("An activation without a lease carries no token, and no command asks for one")
  public void withoutALeaseNoCommandCarriesAToken() {

    final var job = mock(ActivatedJob.class);
    assertNull(Camunda8JobLease.tokenOf(job));

    final var completion = mock(CompleteJobCommandStep1.class, RETURNS_SELF);
    assertSame(completion, Camunda8JobLease.withToken(completion, null));
    verify(completion, org.mockito.Mockito.never()).withJobLeaseToken(org.mockito.ArgumentMatchers.any());

  }

  @Test
  @DisplayName("The three answers of a leased job carry its token")
  public void everyAnswerCarriesTheToken() {

    final var completion = mock(CompleteJobCommandStep1.class, RETURNS_SELF);
    Camunda8JobLease.withToken(completion, TOKEN);
    verify(completion).withJobLeaseToken(TOKEN);

    final var failure = mock(FailJobCommandStep1.FailJobCommandStep2.class, RETURNS_SELF);
    Camunda8JobLease.withToken(failure, TOKEN);
    verify(failure).withJobLeaseToken(TOKEN);

    final var bpmnError = mock(ThrowErrorCommandStep1.ThrowErrorCommandStep2.class, RETURNS_SELF);
    Camunda8JobLease.withToken(bpmnError, TOKEN);
    verify(bpmnError).withJobLeaseToken(TOKEN);

  }

  @Test
  @DisplayName("A configured adapter of this line has to say whether it leases")
  public void aConfiguredAdapterHasToDecide() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateJobLease("c8", line -> {
        }));

    assertTrue(failure.getMessage().contains("vanillabp.adapters.c8.job-lease"), failure.getMessage());
    assertTrue(failure.getMessage().contains("use"), failure.getMessage());
    assertTrue(failure.getMessage().contains("do-not-use"), failure.getMessage());
    assertTrue(
        failure.getMessage().contains("cannot be taken back"),
        "the message says why there is no default: "
            + failure.getMessage());

  }

  @Test
  @DisplayName("An adapter without an address is asked too, because it opens workers on the local cluster")
  public void anAdapterWithoutAnAddressIsAskedToo() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> new Camunda8AdapterConfiguration().validateJobLease("c8", line -> {
        }));

    assertTrue(failure.getMessage().contains("vanillabp.adapters.c8.job-lease"), failure.getMessage());

  }

  @Test
  @DisplayName("Either value boots, and only 'use' leases")
  public void eitherValueBoots() {

    assertTrue(configuredWith(Camunda8AdapterConfiguration.JobLease.USE).leasesItsJobs());
    assertEquals(
        false,
        configuredWith(Camunda8AdapterConfiguration.JobLease.DO_NOT_USE).leasesItsJobs());

  }

  private static Camunda8AdapterConfiguration configuredWith(
      final Camunda8AdapterConfiguration.JobLease jobLease) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.setJobLease(jobLease);
    configuration.validateJobLease("c8", line -> {
    });
    return configuration;

  }

}
