package io.vanillabp.camunda8.springboot;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;

/**
 * A test of this module which runs against the one cluster the module starts.
 * <p>
 * Every class here used to bring a cluster of its own. They all run in the same JVM - one
 * fork per module is what Failsafe does - so one container can serve all of them, and the
 * module starts one cluster instead of some thirty. The container is started when the first
 * class asks for its address and it is never stopped, see
 * {@link ClusterUnderTest#sharedCluster()}.
 * <p>
 * The price is what the cluster remembers. All classes of this module deploy the same files
 * under the same workflow module id, so the cluster holds one set of definitions rather than
 * one per class, and Camunda 8 answers a file it already holds with the version it already
 * has. What a class leaves RUNNING is the part which needs doing something about: prefixed
 * job types are the same for every class, so the workers of the class running next would
 * activate the jobs of a workflow nobody is waiting for any more, and look for a workflow
 * aggregate their own database never held. That is why everything still running is ended after
 * EVERY test, the guard {@link WhatAClassLeftRunning} looks once more after every class, and
 * every class starts by ending what is still running. The first narrows the window in which
 * one test can spoil the next, the second names the class which left something, and the third
 * catches what the search had not shown yet.
 * <p>
 * What the cluster remembers also decides what a class can FIND on it. A search which names
 * nothing answers with ONE page, oldest entry first, and that page has a ceiling. Measured on
 * 2026-10-01 against {@code camunda/camunda:8.10.0}: this module's cluster held 106 process
 * definitions halfway through a run, a search naming nothing answered with 100 of them, and the
 * two deployed last were not among those 100. An explicit {@code page(limit(1000))} answered
 * with all 106, which is what the searches below rely on.
 * <p>
 * So a class names what it is asking about in the filter instead of keeping the entries of an
 * unnamed page whose id ends the right way. A class running late in the module finds nothing of
 * its own that way, and the failure reads like an exporter which never caught up.
 * {@code Camunda8OldProcessVersionsIT} spent two raised deadlines on exactly that.
 * <p>
 * What a class reads and what it cancels are two different things. The workflows come from the
 * search, which answers out of the secondary storage, and the cancellation goes to the engine.
 * A class which only sent its cancellations went on while the engine still held them, and that
 * cost a whole nightly run.
 * <p>
 * A cancellation the engine took is not an instance which ended, and that is the second half of
 * the cleanup. An instance carrying a Camunda-managed user task waits for the
 * <code>canceling</code> listener job of that task before it terminates, and while no
 * application runs nobody answers that job. Measured against a cluster of the 8.9 line on
 * 2026-09-25: the engine answered <code>404</code> to a second cancellation 24 ms after the
 * first one, the instance was still reported as running 130 seconds later, and it ended 0,5
 * seconds after a worker took the listener job. That is where the "a minute after the
 * cancellation" of the earlier measurement came from - not from a search lagging behind. So
 * this cleanup answers those listener jobs itself, and only then is the cluster free of what
 * the class before it left.
 * <p>
 * The search is still the slower of the two, by about a second. A test which looks a workflow
 * up by a variable therefore binds its search to a process of its own, rather than trusting
 * that what comes back belongs to it.
 * <p>
 * Two kinds of test keep a cluster of their own, and each of them says so where it stands.
 * One needs the cluster CONFIGURED differently, with authentication switched on, which makes
 * it a different thing under test. The other needs a cluster which has never seen its model,
 * because the cluster acts on a deployment once and never again: a timer start event fires
 * for the first deployment of its version and for no later one. Both declare a
 * {@code @Container} field, the way every class here did before.
 * <p>
 * <b>Why this module configures {@code max-http-connections}.</b> The adapter opens one worker
 * per process and kind, and each of them holds a REST activation request open. The Camunda
 * client caps its pool at 100 connections by default, the same number in the 8.8, 8.9 and 8.10
 * clients. This module deploys 35 processes, which is 85 workers on the GA lines and 115 on
 * 8.10, where a cancel listener per process comes on top. Above the cap the surplus workers
 * take turns, and whatever one of them is waiting for arrives a whole {@code request-timeout}
 * late. Measured on 2026-09-26 with {@code Camunda8RestartDeliveryIT} at 115 workers on
 * {@code camunda/camunda:8.10.0-rc1}: 10412 ms with the client's 100 and 215 ms with the 256
 * {@code camunda8-it.yaml} now sets. The same line with the 92 workers this module opened
 * before the start-event listener of story 653 answers in 184 ms, and 8.10 held to the 85
 * workers of the GA lines drains cleanly, so it is the number of workers against the size of
 * the pool and not the version of the client. The setting lives in the YAML without a comment
 * beside it because Spotless formats these files through Jackson, which drops comments.
 * <p>
 * A user task left between two of its states is what the cleanup below watches hardest for,
 * and it is worth knowing why. Such a task holds a listener job which stays activatable, and
 * the first worker of that job type in the next class is served it. The 8.10 alphas could not
 * end such a task at all, which is why the preview line once excluded every test creating one;
 * {@code 8.10.0-rc1} hands the jobs out and the exclusions are gone.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(TestOnTheSharedCluster.WhatAClassLeftRunning.class)
public abstract class TestOnTheSharedCluster {

  /**
   * @return Where the shared cluster answers REST requests
   */
  public static String restAddress() {

    return ClusterUnderTest.restAddress(ClusterUnderTest.sharedCluster());

  }

  /**
   * @return Where the shared cluster answers gRPC requests
   */
  public static String grpcAddress() {

    return ClusterUnderTest.grpcAddress(ClusterUnderTest.sharedCluster());

  }

  /**
   * Takes everything the class before left on the shared cluster away from this one, before
   * the application of this class boots.
   * <p>
   * JUnit calls it after the extensions of the class and before the first test instance is
   * built, which is when Spring loads the test's context. So by the time this runs, the
   * application of the class before is closed and this class has not opened a worker yet.
   * <p>
   * Since every test ends what it left and {@link WhatAClassLeftRunning} looks once more after
   * the class, this normally finds nothing. It stays because the search lags behind the
   * engine: a workflow started just before a class closed can be invisible to both of them and
   * turn up only here.
   *
   * @param whichClassThisIs The class taking the cluster over, for the message a cleanup
   *          which cannot finish writes about the class before it
   * @throws InterruptedException Where the wait is interrupted
   */
  @BeforeAll
  static void nothingOfTheClassBeforeReachesThisOne(
      final TestInfo whichClassThisIs) throws InterruptedException {

    final var startedAt = System.nanoTime();
    endEverythingStillRunning(classWhichRanBefore);
    classWhichRanBefore = nameOf(whichClassThisIs);
    waitOutAnActivationRequestOfTheClassBefore(startedAt);
    aParkedRequestOfTheClassBeforeLivesAtMost = WHAT_THIS_MODULE_CONFIGURES;

  }

  /**
   * Ends whatever the test which just finished left running, before the next test of the same
   * class starts.
   * <p>
   * A class used to be cleaned up only when the NEXT class took the cluster over, so one test
   * which left a user task open could poison every class after it. The class which then failed
   * was never the one which caused it. Ending everything after each test makes that window
   * one test wide. It is not airtight: the search answers out of the exporter and lags behind
   * the engine, so a workflow the test started a moment ago can still be invisible here.
   * {@link WhatAClassLeftRunning} is the second look.
   * <p>
   * The application of the class is still running while this runs, so its own workers may
   * answer a <code>canceling</code> listener job before this cleanup does. Either answer ends
   * the workflow.
   * <p>
   * JUnit runs this after every {@code @AfterEach} method of the subclass. A test which
   * completes its own workflows there has done so before this looks.
   *
   * @param whichTestThisWas The test which just finished, for the message and for the cost
   */
  @AfterEach
  void nothingOfThisTestReachesTheNextOne(
      final TestInfo whichTestThisWas) {

    if (getClass().isAnnotationPresent(ItsTestsAreOneScenario.class)) {
      return;
    }
    final var startedAt = System.nanoTime();
    final var ended = endEverythingStillRunning(
        "test '%s' of %s".formatted(
            whichTestThisWas
                .getTestMethod()
                .map(Method::getName)
                .orElse(whichTestThisWas.getDisplayName()),
            nameOf(whichTestThisWas)));
    recordWhatTheCleanupCost(whichTestThisWas, ended, System.nanoTime() - startedAt);

  }

  /**
   * Ends what a class whose tests are one scenario left running, once its last test is done.
   * The application of the class is still running, the same as in
   * {@link #nothingOfThisTestReachesTheNextOne(TestInfo)}.
   *
   * @param whichClassThisIs The class which is done
   */
  @AfterAll
  static void theScenarioEndsWithTheClass(
      final TestInfo whichClassThisIs) {

    final var testClass = whichClassThisIs.getTestClass();
    if (testClass.isEmpty() || !testClass.get().isAnnotationPresent(ItsTestsAreOneScenario.class)) {
      return;
    }
    endEverythingStillRunning(nameOf(whichClassThisIs));

  }

  /**
   * Says that the tests of a class build on each other: a later test needs a workflow an
   * earlier one started. Such a class is cleaned up once, after its last test, instead of after
   * every test. A class carrying it orders its tests with {@code @TestMethodOrder}, because
   * that order is what the scenario relies on.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface ItsTestsAreOneScenario {
  }

  /**
   * Looks at the cluster once more after a class is done, and fails that class if something of
   * it is still running.
   * <p>
   * It runs after the application of the class is closed. JUnit registers the extensions of a
   * superclass before those of a subclass and calls them back in reverse order after the
   * class, so Spring has closed the context of the class (it is {@code @DirtiesContext}) by
   * the time this looks. What it finds was left by an application on its way down, or by a
   * test whose cleanup the search did not catch up with. Either way the class to read is this
   * one, and the message says so. Before this guard, the same leftover failed the NEXT class
   * in its setup, which named a class that had done nothing wrong.
   * <p>
   * What it finds is ended before the class fails, so the class after it still gets a clean
   * cluster.
   */
  static class WhatAClassLeftRunning implements AfterAllCallback {

    @Override
    public void afterAll(
        final ExtensionContext context) {

      if (whyTheClusterCannotBeHandedOver != null) {
        // a cleanup already gave up and said why; the next class says it again
        return;
      }
      final var whoLeftIt = context
          .getTestClass()
          .map(Class::getSimpleName)
          .orElse("this class");
      final List<String> leftRunning;
      try (final var client = clientOfTheTest()) {
        waitUntilTheSearchHasCaughtUp(client);
        leftRunning = whatIsStillRunning(client);
      }
      if (leftRunning.isEmpty()) {
        return;
      }
      endEverythingStillRunning(whoLeftIt);
      throw new AssertionError(
          ("%s left %d workflow(s) or user task(s) running on the shared cluster after its "
              + "application was closed. They are ended now, so the next class is not "
              + "affected, but a test of this class has to end what it starts: %s")
              .formatted(whoLeftIt, leftRunning.size(), String.join("", leftRunning)));

    }

  }

  /**
   * What the search reports as running, one line per entry, for the guard's message.
   *
   * @param client The client of the test
   * @return Nothing where the cluster is clean
   */
  private static List<String> whatIsStillRunning(
      final CamundaClient client) {

    final var found = new ArrayList<String>();
    stillRunning(client)
        .stream()
        .map(workflow -> "%n  workflow %d of '%s'".formatted(
            workflow.getProcessInstanceKey(),
            workflow.getProcessDefinitionId()))
        .forEach(found::add);
    userTasksBetweenTwoStates(client)
        .stream()
        .map(task -> "%n  user task '%s' of '%s' in %s, instance %d".formatted(
            task.getElementId(),
            task.getBpmnProcessId(),
            task.getState(),
            task.getProcessInstanceKey()))
        .forEach(found::add);
    return found;

  }

  private static String nameOf(
      final TestInfo test) {

    return test
        .getTestClass()
        .map(Class::getSimpleName)
        .orElse("the class before");

  }

  /**
   * The class whose leftovers the next cleanup takes away, so a cleanup which cannot finish
   * names it instead of leaving the reader to work out the order of the classes.
   */
  private static String classWhichRanBefore = "the class before";

  /**
   * Why the cluster can serve no further class, once one cleanup has found that out. Every
   * class after that fails at once with the same sentence rather than sitting out its own
   * minute for an answer which cannot change.
   */
  private static String whyTheClusterCannotBeHandedOver;

  /**
   * Ends everything the cluster still runs, and returns only once every workflow it found has
   * ended.
   * <p>
   * The search finds what to end. It does NOT decide when the cleanup is done, because it
   * answers out of the exporter and lags behind the engine. An earlier version stopped once the
   * user-task search was empty, and it could stop before a task it had just cancelled showed up
   * as <code>CANCELING</code>. The worker of the next application was then served that task's
   * <code>canceling</code> listener job, for a workflow aggregate in a database which had died
   * with the class before. So each workflow found is asked for by its key until it reports an
   * end state of its own, and until then this answers every listener job of this adapter which
   * belongs to one of these workflows.
   * <p>
   * An instance carrying a Camunda-managed user task terminates only once the
   * <code>canceling</code> listener job of that task is answered, and while no application
   * runs nobody answers it. Measured on 2026-09-25 against <code>8.10.0-alpha5</code> and
   * <code>8.9.21</code>: 130 seconds after the cancellation the instance was still alive, and
   * it ended 0.52 seconds after a worker took the job.
   * <p>
   * A child of a call activity is not cancelled: the engine refuses to cancel one, and
   * cancelling its parent ends it anyway, so it goes with the parent.
   *
   * @param whoLeftIt The class or test the leftovers belong to, for the message where this
   *          gives up
   * @return How many workflows had to be ended
   */
  private static int endEverythingStillRunning(
      final String whoLeftIt) {

    if (whyTheClusterCannotBeHandedOver != null) {
      throw new IllegalStateException(whyTheClusterCannotBeHandedOver);
    }
    try (final var client = clientOfTheTest()) {
      waitUntilTheSearchHasCaughtUp(client);
      final var deadline = System.currentTimeMillis() + THE_ENGINE_LETS_GO_WITHIN.toMillis();
      final var toEnd = new LinkedHashMap<Long, Long>();
      final var refusals = new LinkedHashMap<Long, String>();
      while (true) {
        stillRunning(client)
            .stream()
            .filter(workflow -> workflow.getParentProcessInstanceKey() == null)
            .filter(workflow -> !toEnd.containsKey(workflow.getProcessInstanceKey()))
            .forEach(workflow -> {
              cancel(client, workflow.getProcessInstanceKey(), refusals);
              toEnd.put(workflow.getProcessInstanceKey(), workflow.getProcessDefinitionKey());
            });
        // a task between two states belongs to a workflow which is ending already, possibly
        // a child the search above passed over. Its workflow is waited for the same way
        userTasksBetweenTwoStates(client)
            .forEach(task -> toEnd.putIfAbsent(task.getProcessInstanceKey(), task.getProcessDefinitionKey()));
        answerTheListenerJobsOf(client, toEnd);
        final var notOverYet = toEnd
            .keySet()
            .stream()
            .filter(processInstanceKey -> !hasEnded(client, processInstanceKey))
            .toList();
        if (notOverYet.isEmpty()) {
          return toEnd.size();
        }
        if (System.currentTimeMillis() > deadline) {
          whyTheClusterCannotBeHandedOver = whyItCouldNotBeEnded(client, whoLeftIt, notOverYet, refusals);
          throw new IllegalStateException(whyTheClusterCannotBeHandedOver);
        }
        pauseBeforeAskingAgain();
      }
    }

  }

  /**
   * Waits until the search knows every workflow the engine had started when this was called.
   * <p>
   * A test which starts a workflow and ends a moment later leaves a workflow the search does
   * not show yet. A cleanup which searched at once missed it, and it then ran on into the next
   * test. Measured on 2026-10-06 against {@code camunda/camunda:8.10.0}: a test of
   * {@code Camunda8InboundIdempotencyIT} ended 12 ms after creating its workflow, the cleanup
   * found nothing, and the guard after the class found the workflow still running.
   * <p>
   * So this starts a workflow of its own, of a process which ends where it starts, and waits
   * until that workflow can be asked for by its key. The cluster of this module runs one
   * partition, which is the default of the image, its keys grow with every workflow started
   * and its exporter writes in that order. So once this marker is in the search, every
   * workflow started before it is there as well.
   *
   * @param client The client of the test
   */
  private static void waitUntilTheSearchHasCaughtUp(
      final CamundaClient client) {

    if (!theMarkerIsDeployed) {
      whatTheClusterAnswers(
          () -> client
              .newDeployResourceCommand()
              .addResourceStringUtf8(A_PROCESS_WHICH_ENDS_WHERE_IT_STARTS, THE_MARKER
                  + ".bpmn")
              .send()
              .join());
      theMarkerIsDeployed = true;
    }
    final var marker = client
        .newCreateInstanceCommand()
        .bpmnProcessId(THE_MARKER)
        .latestVersion()
        .send()
        .join()
        .getProcessInstanceKey();
    final var deadline = System.currentTimeMillis() + SEARCHABLE_WITHIN.toMillis();
    while (true) {
      try {
        client
            .newProcessInstanceGetRequest(marker)
            .send()
            .join();
        return;
      } catch (final RuntimeException notThereYet) {
        if (System.currentTimeMillis() > deadline) {
          throw notThereYet;
        }
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the cluster's search", interrupted);
      }
    }

  }

  private static boolean theMarkerIsDeployed;

  /**
   * The process of the marker. Its id carries no prefix of a workflow module, so no
   * application of this module serves it or counts it as one of its own.
   */
  private static final String THE_MARKER = "theCleanupOfTheSharedClusterLooksHere";

  private static final String A_PROCESS_WHICH_ENDS_WHERE_IT_STARTS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%1$s" isExecutable="true">
          <bpmn:startEvent id="Start"><bpmn:outgoing>Flow</bpmn:outgoing></bpmn:startEvent>
          <bpmn:sequenceFlow id="Flow" sourceRef="Start" targetRef="End" />
          <bpmn:endEvent id="End"><bpmn:incoming>Flow</bpmn:incoming></bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(THE_MARKER);

  /**
   * Whether one workflow reports an end state of its own.
   * <p>
   * This asks for the instance by its key rather than searching, so the answer is about this
   * workflow and nothing else. It still comes out of the secondary storage, which writes an
   * end only after the engine reached it. So an end reported here is an end the engine is past.
   * A key the storage does not know yet is a workflow which is not over, because it was found
   * by a search of the same storage.
   *
   * @param client The client of the test
   * @param processInstanceKey The workflow
   * @return Whether it completed or was terminated
   */
  private static boolean hasEnded(
      final CamundaClient client,
      final long processInstanceKey) {

    try {
      return client
          .newProcessInstanceGetRequest(processInstanceKey)
          .send()
          .join()
          .getState() != ProcessInstanceState.ACTIVE;
    } catch (final RuntimeException notThereYet) {
      if (Camunda8Errors.notFound(notThereYet)) {
        return false;
      }
      throw notThereYet;
    }

  }

  /**
   * The user tasks the cluster holds between two of their states.
   * <p>
   * Each of them waits for a listener job of this adapter's own, and each of those jobs is
   * activatable until somebody answers it. The search is how a child workflow's task is
   * found, whose workflow the search for running roots passes over.
   *
   * @param client The client of the test
   * @return What the cluster still has to be told about
   */
  private static List<UserTask> userTasksBetweenTwoStates(
      final CamundaClient client) {

    return whatTheClusterAnswers(
        () -> client
            .newUserTaskSearchRequest()
            .filter(filter -> filter
                .state(state -> state.in(UserTaskState.CREATING, UserTaskState.CANCELING)))
            // one page for the whole module: a class leaves a handful of user tasks behind,
            // and a second page would be a loop against a storage which is still filling up
            .page(page -> page.limit(1000))
            .send()
            .join()
            .items());

  }

  /**
   * Answers the listener jobs of this adapter which belong to the workflows being ended, the
   * way the worker of the application would have done.
   * <p>
   * The job types come from the models of those workflows, read once per process definition,
   * and not from a search. That is the point: a task the search does not show yet has its
   * job type in the model all the same. Every listener in the model whose job type carries this
   * adapter's prefix counts, the <code>canceling</code> listener of a user task as well as a
   * listener the engine runs when a workflow is cancelled.
   * <p>
   * A job of such a type which belongs to a workflow NOT being ended is handed back at once,
   * with the retries it had. While the application of a class runs, that is a job of a
   * workflow its test started a moment ago, which the search did not show yet.
   *
   * @param client The client of the test
   * @param toEnd The workflows being ended, each with the key of its process definition
   */
  private static void answerTheListenerJobsOf(
      final CamundaClient client,
      final Map<Long, Long> toEnd) {

    toEnd
        .values()
        .stream()
        .distinct()
        .flatMap(processDefinitionKey -> listenerJobTypesOf(client, processDefinitionKey).stream())
        .distinct()
        // all job types are asked at once: each request waits its timeout where no job is
        // there, and a model of this module carries up to twenty such types
        .map(jobType -> client
            .newActivateJobsCommand()
            .jobType(jobType)
            .maxJobsToActivate(AS_MANY_AS_A_CLASS_CAN_LEAVE)
            .timeout(THE_CLEANUP_HOLDS_A_JOB_FOR)
            .workerName("the cleanup of the shared cluster")
            .requestTimeout(ASKING_FOR_A_LEFTOVER_JOB_ANSWERS_WITHIN)
            .send())
        .toList()
        .forEach(activation -> activation
            .join()
            .getJobs()
            .forEach(job -> {
              if (toEnd.containsKey(job.getProcessInstanceKey())) {
                Camunda8JobLease
                    .withToken(client.newCompleteCommand(job.getKey()), Camunda8JobLease.tokenOf(job))
                    .send()
                    .join();
                return;
              }
              Camunda8JobLease
                  .withToken(
                      client
                          .newFailCommand(job.getKey())
                          .retries(job.getRetries())
                          .retryBackoff(Duration.ZERO),
                      Camunda8JobLease.tokenOf(job))
                  .send()
                  .join();
            }));

  }

  /**
   * The job types of the listeners of this adapter in one process definition. A process
   * definition never changes, so each is read once per fork.
   *
   * @param client The client of the test
   * @param processDefinitionKey The process definition
   * @return The job types, empty for a model without such a listener
   */
  private static Set<String> listenerJobTypesOf(
      final CamundaClient client,
      final Long processDefinitionKey) {

    if (processDefinitionKey == null) {
      return Set.of();
    }
    return LISTENER_JOB_TYPES.computeIfAbsent(processDefinitionKey, key -> {
      final var xml = whatTheClusterAnswers(
          () -> client
              .newProcessDefinitionGetXmlRequest(key)
              .send()
              .join());
      final var jobTypes = new LinkedHashSet<String>();
      final var listener = A_LISTENER_OF_THIS_ADAPTER.matcher(xml);
      while (listener.find()) {
        jobTypes.add(unescaped(listener.group(1)));
      }
      return jobTypes;
    });

  }

  private static final Map<Long, Set<String>> LISTENER_JOB_TYPES = new ConcurrentHashMap<>();

  /**
   * A task listener or an execution listener whose job type carries this adapter's prefix.
   * The deployment writes these attributes itself, so their shape is known.
   */
  private static final Pattern A_LISTENER_OF_THIS_ADAPTER = Pattern.compile(
      "<zeebe:(?:taskListener|executionListener)\\b[^>]*\\btype=\"("
          + Pattern.quote(Camunda8Listeners.VANILLABP_JOB_TYPE_PREFIX)
          + "[^\"]*)\"");

  private static String unescaped(
      final String attribute) {

    return attribute
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&");

  }

  /**
   * @see #answerTheListenerJobsOf(CamundaClient, Map)
   */
  private static final int AS_MANY_AS_A_CLASS_CAN_LEAVE = 100;

  /**
   * @see #answerTheListenerJobsOf(CamundaClient, Map)
   */
  private static final Duration THE_CLEANUP_HOLDS_A_JOB_FOR = Duration.ofSeconds(10);

  /**
   * How long the cleanup waits for a job of a listener type. Short, because the cleanup asks
   * again on its next round anyway, and every round pays this wait once per job type that has
   * no job at the moment.
   */
  private static final Duration ASKING_FOR_A_LEFTOVER_JOB_ANSWERS_WITHIN = Duration.ofMillis(200);

  /**
   * What a cleanup says when it gives up.
   *
   * @param client The client of the test
   * @param whoLeftIt The class or test the leftovers belong to
   * @param notOverYet The workflows which did not report an end
   * @param refusals What the engine answered per instance, where it refused the cancellation
   * @return The sentence a reader of a red run gets
   */
  private static String whyItCouldNotBeEnded(
      final CamundaClient client,
      final String whoLeftIt,
      final List<Long> notOverYet,
      final Map<Long, String> refusals) {

    return ("%d workflow(s) which %s left did not end within %s after this cleanup cancelled "
        + "them and answered their listener jobs, so the next application would be served "
        + "their jobs: %s%s%nA workflow which does not end after its listener jobs were answered "
        + "is a cluster which cannot hand those jobs out. Read the cluster's log before "
        + "looking for the cause in this repository.")
        .formatted(
            notOverYet.size(),
            whoLeftIt,
            THE_ENGINE_LETS_GO_WITHIN,
            notOverYet
                .stream()
                .map(key -> "%n  instance %d%s".formatted(
                    key,
                    refusals.containsKey(key)
                        ? ", the engine refused to cancel it: "
                            + refusals.get(key)
                        : ""))
                .collect(Collectors.joining()),
            String.join("", whatIsStillRunning(client)));

  }

  /**
   * Appends what one cleanup cost to a file of the module's build directory, one line per
   * test: the class, the test, the milliseconds and how many workflows it ended. It is how the
   * price of cleaning up after every test is read off a run.
   *
   * @param test The test which just finished
   * @param ended How many workflows the cleanup ended
   * @param nanos How long the cleanup took
   */
  private static void recordWhatTheCleanupCost(
      final TestInfo test,
      final int ended,
      final long nanos) {

    final var line = "%s;%s;%d;%d%n".formatted(
        nameOf(test),
        test
            .getTestMethod()
            .map(Method::getName)
            .orElse(test.getDisplayName()),
        Duration.ofNanos(nanos).toMillis(),
        ended);
    try {
      Files.writeString(
          WHERE_THE_COST_IS_WRITTEN,
          line,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (final IOException notWritten) {
      // the cost is a reading for a person, and a test does not fail over it
    }

  }

  private static final Path WHERE_THE_COST_IS_WRITTEN = Path.of("target", "shared-cluster-cleanup.csv");

  /**
   * How long the cleanup may take to get the cluster free of the class before. Generous rather
   * than measured: the ordinary round ends at once, and what this number decides is when a
   * leftover nothing can end fails the class instead of being handed on.
   */
  private static final Duration THE_ENGINE_LETS_GO_WITHIN = Duration.ofSeconds(60);

  /**
   * How long an activation request of a client which was already closed can still be parked
   * at the cluster. Such a request is a long poll, closing the client does not cancel it, and
   * a job created while it is parked is activated into it and answered by nobody until its
   * lock expires.
   * <p>
   * It is the <code>request-timeout</code> the applications of this module run with, and the
   * yaml files of this module set it to the same five seconds. The client's own default is ten,
   * and that is what this module used to pay twice per class: once here, and once more in the
   * drain of the class before, which cannot report its workers closed until their parked
   * requests come back. Measured on 2026-09-25 with the default: 281,9 seconds of waiting here
   * across twenty-nine classes, in a module which took 985 seconds.
   * <p>
   * Five seconds is above the second below which the adapter calls the value unusable, and
   * above what this module's commands need on a developer machine, where its deployment of
   * nineteen files took under a second. Two seconds were tried first and the build runner
   * missed them: on 2026-09-25 the deployment of that same module answered with
   * <code>SocketTimeoutException: 2000 MILLISECONDS</code> on line 8.8, in a job which passes
   * with five. A runner is slower than the machine a measurement is taken on, and this value
   * has to hold for both. A class which gives its applications a longer window says so with
   * {@link #aRequestOfThisClassCanBeParkedFor(Duration)}.
   */
  private static final Duration WHAT_THIS_MODULE_CONFIGURES = Duration.ofSeconds(5);

  /**
   * Whether a class of this module has run in this fork already. The first one talks to a
   * cluster nobody has opened a worker against, so it has nothing to wait for.
   */
  private static boolean aClassHasRunBefore;

  /**
   * Waits until an activation request of the class before cannot be parked at the cluster any
   * more.
   * <p>
   * The drain of a workflow module waits for the cluster to release its workers, so an
   * ordinary shutdown leaves no such request. One which ran out of its grace does, and the
   * class after it is what pays: its first job is activated into that request and comes back
   * only when its lock expires. The cluster cannot be asked about it, and nothing but time
   * closes it, so this waits the window out.
   * <p>
   * The window is measured from the start of the cleanup above rather than from the moment
   * the class before closed its client, which is earlier: JUnit has run every callback of
   * that class by the time this class begins. So the wait is at least as long as it has to
   * be, and the cleanup above pays for the part of it which it took.
   *
   * @param theCleanupStartedAt When this class began taking the cluster over, in nanoseconds
   * @throws InterruptedException Where the wait is interrupted
   */
  private static void waitOutAnActivationRequestOfTheClassBefore(
      final long theCleanupStartedAt) throws InterruptedException {

    if (!aClassHasRunBefore) {
      aClassHasRunBefore = true;
      return;
    }
    final var left = aParkedRequestOfTheClassBeforeLivesAtMost
        .minusNanos(System.nanoTime() - theCleanupStartedAt);
    if (left.isPositive()) {
      Thread.sleep(left.toMillis());
    }

  }

  /**
   * How long a request of the class before can still be parked. It is what that class
   * configured, which is the module's value unless the class said otherwise.
   *
   * @see #aRequestOfThisClassCanBeParkedFor(Duration)
   */
  private static Duration aParkedRequestOfTheClassBeforeLivesAtMost = WHAT_THIS_MODULE_CONFIGURES;

  /**
   * Says that a request of THIS class can be parked longer than the module configures, so the
   * class after it waits that window out instead of the short one.
   * <p>
   * A class calls it from a {@code @BeforeAll} of its own, which JUnit runs after the one
   * above. The window is read at the start of the next class and set back to the module's
   * value there, so it is never carried further than one class.
   *
   * @param window The <code>request-timeout</code> this class gives its applications
   */
  protected static void aRequestOfThisClassCanBeParkedFor(
      final Duration window) {

    aParkedRequestOfTheClassBeforeLivesAtMost = window;

  }

  /**
   * The workflows the cluster still reports as running.
   *
   * @param client The client of the test
   * @return What an earlier class left, as the search sees it
   */
  private static List<ProcessInstance> stillRunning(
      final CamundaClient client) {

    return whatTheClusterAnswers(
        () -> client
            .newProcessInstanceSearchRequest()
            .filter(filter -> filter.state(ProcessInstanceState.ACTIVE))
            // one page for the whole module: a class leaves a handful of workflows behind,
            // and a second page would be a loop against a storage which is still filling up
            .page(page -> page.limit(1000))
            .send()
            .join()
            .items());

  }

  /**
   * What the cluster answers with, retried while it refuses to answer at all.
   * <p>
   * The class which starts the container is the one which meets a cluster whose search is
   * not up yet: it is ready to take a deployment before it is ready to be asked what it
   * holds. Every class after that meets a cluster which has been answering for a while, so a
   * refusal which outlasts the retries is a broken search rather than a cold one, and it
   * ends the class instead of leaving the workflows of an earlier one running.
   *
   * @param <T> What the search brings back
   * @param search The search to run
   * @return Its answer
   */
  private static <T> T whatTheClusterAnswers(
      final Supplier<T> search) {

    final var deadline = System.currentTimeMillis() + SEARCHABLE_WITHIN.toMillis();
    while (true) {
      try {
        return search.get();
      } catch (final RuntimeException refused) {
        if (System.currentTimeMillis() > deadline) {
          throw refused;
        }
        pauseBeforeAskingAgain();
      }
    }

  }

  /**
   * @see #whatTheClusterAnswers(Supplier)
   */
  private static final Duration SEARCHABLE_WITHIN = Duration.ofSeconds(60);

  private static void pauseBeforeAskingAgain() {

    try {
      Thread.sleep(500);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the cluster's search", interrupted);
    }

  }

  /**
   * Cancels one workflow.
   * <p>
   * A <code>404</code> means the engine takes no cancellation for this key, which is either a
   * workflow that is over or one which is terminating already. Neither says that it is over,
   * which is why the caller asks the workflow itself afterwards. Every other refusal is kept
   * for the message a caller writes where it gives up, because a cancellation refused for some
   * other reason is what a red run has to be able to read.
   *
   * @param client The client of the test
   * @param processInstanceKey The workflow to cancel
   * @param refusals What the engine answered, per instance
   */
  private static void cancel(
      final CamundaClient client,
      final long processInstanceKey,
      final Map<Long, String> refusals) {

    try {
      client
          .newCancelInstanceCommand(processInstanceKey)
          .send()
          .join();
    } catch (final RuntimeException refused) {
      if (!Camunda8Errors.notFound(refused)) {
        refusals.put(processInstanceKey, refused.getMessage());
      }
    }

  }

  /**
   * A client of the test's own: this runs while no application of the test is up.
   */
  private static CamundaClient clientOfTheTest() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

}
