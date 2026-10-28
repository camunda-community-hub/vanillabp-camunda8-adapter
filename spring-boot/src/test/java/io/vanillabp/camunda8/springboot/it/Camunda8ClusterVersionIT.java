package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.camunda8.Camunda8ReleaseLine;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A start against a real cluster compares its version with the client. The cluster of these
 * tests is built from the pin of the line, so the two have the same minor version. The start
 * says so in one INFO line, and it warns about nothing. See decision 90 in the repository's
 * DECISIONS.md.
 * <p>
 * The other verdicts need a cluster of another minor version. Starting one for each would
 * cost minutes and memory. The unit test of the check covers them instead.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ClusterVersionIT extends TestOnTheSharedCluster {

  @Test
  @DisplayName("A cluster of the line's minor version is named in one INFO line, and nothing is warned about")
  public void aClusterOfTheSameMinorIsAnInfo(
      final CapturedOutput output) {

    new SpringApplicationBuilder(DockerTestApplication.class)
        .run(
            "--spring.config.name=camunda8-it",
            "--spring.main.web-application-type=none",
            "--vanillabp.adapters.c8.rest-address="
                + restAddress(),
            "--vanillabp.adapters.c8.grpc-address="
                + grpcAddress())
        .close();

    final var logged = output.getOut() + output.getErr();
    final var expected = "and client %s of adapter release line %s have the same minor version"
        .formatted(Camunda8ReleaseLine.clientVersion(), Camunda8ReleaseLine.id());
    assertTrue(
        logged.contains(expected),
        "the start compared the cluster with the client: "
            + logged);
    assertTrue(
        logged.contains("Camunda8[c8]: cluster "
            + Camunda8ReleaseLine.id()
            + "."),
        "and named the version the cluster answered with: "
            + logged);
    assertFalse(
        logged.contains("minor version newer") || logged.contains("minor version older"),
        "a cluster of the same minor version is not warned about: "
            + logged);

  }

}
