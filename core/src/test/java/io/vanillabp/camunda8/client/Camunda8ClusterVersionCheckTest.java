package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.Camunda8ReleaseLine;
import io.vanillabp.camunda8.client.Camunda8ClusterVersionCheck.Severity;
import io.vanillabp.camunda8.client.Camunda8ClusterVersionCheck.Verdict;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the start says about the cluster it found, compared with the client it was built
 * against. Only the minor version counts, and no pair ends the start. See decision 90 in the
 * repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ClusterVersionCheckTest {

  private static Verdict judge(
      final String cluster,
      final String client) {

    return Camunda8ClusterVersionCheck.judge("c8", cluster, client, "8.10");

  }

  /**
   * Every line names both versions and the release line, whatever it says about them.
   */
  private static void assertNamesThePair(
      final Verdict verdict,
      final String cluster,
      final String client) {

    assertTrue(verdict.message().startsWith("Camunda8[c8]: "), "names the adapter id: "
        + verdict);
    assertTrue(verdict.message().contains(cluster), "names the cluster version: "
        + verdict);
    assertTrue(verdict.message().contains(client), "names the client version: "
        + verdict);
    assertTrue(verdict.message().contains("adapter release line 8.10"), "names the release line: "
        + verdict);
    assertFalse(verdict.message().contains("\n"), "and it is one line: "
        + verdict);

  }

  @Test
  @DisplayName("The same minor version is an INFO, whatever the patch levels are")
  public void theSameMinorIsAnInfo() {

    final var verdict = judge("8.10.0", "8.10.5");

    assertEquals(Severity.INFO, verdict.severity(), verdict.message());
    assertNamesThePair(verdict, "8.10.0", "8.10.5");
    assertTrue(verdict.message().contains("same minor version"), verdict.message());

  }

  @Test
  @DisplayName("A cluster one minor version newer is a WARN which names the line of the cluster")
  public void aClusterOneMinorNewerIsAWarn() {

    final var verdict = judge("8.11.2", "8.10.5");

    assertEquals(Severity.WARN, verdict.severity(), verdict.message());
    assertNamesThePair(verdict, "8.11.2", "8.10.5");
    assertTrue(verdict.message().contains("one minor version newer"), verdict.message());
    assertTrue(verdict.message().contains("use adapter release line 8.11 (versions ending in '-8.11')"),
        "the way out is the line of the cluster: "
            + verdict.message());

  }

  @Test
  @DisplayName("A cluster one minor version older is a WARN which says that Camunda support did not confirm it")
  public void aClusterOneMinorOlderIsAWarn() {

    final var verdict = judge("8.9.16", "8.10.5");

    assertEquals(Severity.WARN, verdict.severity(), verdict.message());
    assertNamesThePair(verdict, "8.9.16", "8.10.5");
    assertTrue(verdict.message().contains("one minor version older"), verdict.message());
    assertTrue(verdict.message().contains("Camunda support has not confirmed"), verdict.message());
    assertTrue(verdict.message().contains("use adapter release line 8.9 (versions ending in '-8.9')"),
        "the way out is the line of the cluster: "
            + verdict.message());
    assertTrue(verdict.message().contains("or upgrade the cluster to 8.10"),
        "or a cluster of the client's minor version: "
            + verdict.message());

  }

  @Test
  @DisplayName("A cluster two minor versions older is an ERROR, and the start goes on")
  public void aClusterTwoMinorsOlderIsAnError() {

    final var verdict = judge("8.8.34", "8.10.5");

    assertEquals(Severity.ERROR, verdict.severity(), verdict.message());
    assertNamesThePair(verdict, "8.8.34", "8.10.5");
    assertTrue(verdict.message().contains("more than one minor version older"), verdict.message());
    assertTrue(verdict.message().contains("The start goes on anyway"), verdict.message());
    assertTrue(verdict.message().contains("use adapter release line 8.8"), verdict.message());
    assertTrue(verdict.message().contains("or upgrade the cluster to 8.10"), verdict.message());

  }

  @Test
  @DisplayName("A cluster two minor versions newer is an ERROR which names the line of the cluster")
  public void aClusterTwoMinorsNewerIsAnError() {

    final var verdict = judge("8.12.0", "8.10.5");

    assertEquals(Severity.ERROR, verdict.severity(), verdict.message());
    assertNamesThePair(verdict, "8.12.0", "8.10.5");
    assertTrue(verdict.message().contains("more than one minor version newer"), verdict.message());
    assertTrue(verdict.message().contains("To fix it, use adapter release line 8.12"), verdict.message());
    assertFalse(verdict.message().contains("upgrade the cluster"),
        "a newer cluster is not to be downgraded: "
            + verdict.message());

  }

  @Test
  @DisplayName("Another major version is never one minor version apart")
  public void anotherMajorIsAnError() {

    assertEquals(Severity.ERROR, judge("9.0.0", "8.10.5").severity());
    assertEquals(Severity.ERROR, judge("7.23.0", "8.10.5").severity());

  }

  @Test
  @DisplayName("A pre-release counts by its minor version, on both sides")
  public void aPreReleaseCountsByItsMinor() {

    assertEquals(Severity.WARN, judge("8.11.0-alpha2", "8.10.5").severity());
    assertEquals(Severity.INFO, judge("8.11.0-alpha2", "8.11.0-alpha1").severity());
    assertEquals(Severity.INFO, judge("8.10.0-SNAPSHOT", "8.10.5").severity());
    assertEquals(Severity.INFO, judge("8.10", "8.10.5").severity());

  }

  @Test
  @DisplayName("A version which cannot be read is an INFO saying that nothing was compared")
  public void anUnreadableVersionIsNotCompared() {

    for (final var cluster : new String[]{
        null, "", "dev", "eight.ten", "8", "99999999999.1.0"
    }) {
      final var verdict = judge(cluster, "8.10.5");
      assertEquals(Severity.INFO, verdict.severity(), verdict.message());
      assertTrue(verdict.message().contains("cannot compare cluster version"), verdict.message());
    }

  }

  @Test
  @DisplayName("Without a client version the line stands for it")
  public void theLineStandsForAnUnknownClient() {

    assertEquals(Severity.INFO, judge("8.10.3", "unknown").severity());
    assertEquals(Severity.WARN, judge("8.9.3", "unknown").severity());
    final var nothing = Camunda8ClusterVersionCheck.judge("c8", "8.10.3", "unknown", "unknown");
    assertEquals(Severity.INFO, nothing.severity());
    assertTrue(nothing.message().contains("cannot compare"), nothing.message());

  }

  @Test
  @DisplayName("The verdict is written at its level, compared with the client of this build")
  public void theVerdictIsWrittenAtItsLevel(
      final CapturedOutput output) {

    Camunda8ClusterVersionCheck.logTheVerdict("c8", Camunda8ReleaseLine.id()
        + ".0");
    Camunda8ClusterVersionCheck.logTheVerdict("c8", "8.1.0");
    Camunda8ClusterVersionCheck.logTheVerdict("c8", "7.0.0");
    Camunda8ClusterVersionCheck.logThatNothingWasCompared("c8");

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("have the same minor version"), logged);
    assertTrue(logged.contains("ERROR"), "a cluster far away is an ERROR: "
        + logged);
    assertTrue(logged.contains("'vanillabp.adapters.c8.startup-wait' is PT0S"), logged);

  }

  @Test
  @DisplayName("A cluster one minor version away is written as a WARN")
  public void aWarnIsWrittenAsAWarn(
      final CapturedOutput output) {

    final var line = Camunda8ReleaseLine.id().split("\\.");
    Camunda8ClusterVersionCheck.logTheVerdict(
        "c8", line[0]
            + "."
            + (Integer.parseInt(line[1]) + 1)
            + ".0");

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("WARN"), logged);
    assertTrue(logged.contains("one minor version newer"), logged);

  }

}
