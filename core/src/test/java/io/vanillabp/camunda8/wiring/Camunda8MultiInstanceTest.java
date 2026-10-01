package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Activity;
import io.camunda.zeebe.model.bpmn.instance.CallActivity;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledElement;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeIoMapping;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the adapter has to do to a model before Camunda 8 can report an iteration:
 * every multi-instance element gets input mappings named after itself,
 * and which iterations enclose which element is remembered for dispatch.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8MultiInstanceTest {

  private static final String NESTED = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="MiProcess" isExecutable="true">
          <bpmn:serviceTask id="Flat">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="flat" />
            </bpmn:extensionElements>
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
          </bpmn:serviceTask>
          <bpmn:subProcess id="Outer">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=groups" inputElement="group" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:serviceTask id="Inner">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="inner" />
              </bpmn:extensionElements>
              <bpmn:multiInstanceLoopCharacteristics>
                <bpmn:extensionElements>
                  <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
                </bpmn:extensionElements>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:serviceTask>
            <bpmn:serviceTask id="Plain">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="plain" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
          </bpmn:subProcess>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * One process called from two places, each of them inside a multi-instance element of
   * its own - the shape which makes the chain a union rather than a path.
   */
  private static final String TWO_CALLERS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="CallerA" isExecutable="true">
          <bpmn:subProcess id="LoopA">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=as" inputElement="a" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:callActivity id="CallSharedFromA">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="Shared" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:subProcess>
        </bpmn:process>
        <bpmn:process id="CallerB" isExecutable="true">
          <bpmn:subProcess id="LoopB">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=bs" inputElement="b" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:callActivity id="CallSharedFromB">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="Shared" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:subProcess>
        </bpmn:process>
        <bpmn:process id="Shared" isExecutable="true">
          <bpmn:serviceTask id="SharedTask">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="shared" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * The same two callers, in a file each because one BPMN file may not hold two elements of
   * one ID. Their multi-instance elements carry ONE ID and hand over different things: one
   * of them has no input element at all.
   */
  private static final String ONE_ID_CALLER_A = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="CallerA" isExecutable="true">
          <bpmn:subProcess id="Loop">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=as" inputElement="a" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:callActivity id="CallSharedFromA">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="Shared" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:subProcess>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String ONE_ID_CALLER_B = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="CallerB" isExecutable="true">
          <bpmn:subProcess id="Loop">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=bs" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:callActivity id="CallSharedFromB">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="Shared" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:subProcess>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A third caller whose multi-instance element carries the ID of the one in
   * {@link #ONE_ID_CALLER_A} and hands over the same thing.
   */
  private static final String SAME_ID_CALLER_C = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="CallerC" isExecutable="true">
          <bpmn:subProcess id="Loop">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=cs" inputElement="a" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:callActivity id="CallSharedFromC">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="Shared" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:subProcess>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A process calling itself from inside its own iteration.
   */
  private static final String RECURSION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Recursive" isExecutable="true">
          <bpmn:subProcess id="RecursiveLoop">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=children" inputElement="child" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:serviceTask id="RecursiveTask">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="recursive" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
            <bpmn:callActivity id="CallMyself">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="Recursive" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:subProcess>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * Three call activities: one saying nothing about the variables of the enclosing scopes,
   * one switching them off and one asking for them.
   */
  private static final String PROPAGATION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Caller" isExecutable="true">
          <bpmn:callActivity id="SaysNothing">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="Child" propagateAllChildVariables="false" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
          <bpmn:callActivity id="SaysNo">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="Child" propagateAllParentVariables="false" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
          <bpmn:callActivity id="SaysYes">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="Child" propagateAllParentVariables="true" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A model carrying an input mapping of the name VanillaBP writes, reading something
   * else.
   */
  private static final String CLASHING_MAPPING = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Clashing" isExecutable="true">
          <bpmn:serviceTask id="Ship">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="ship" />
              <zeebe:ioMapping>
                <zeebe:input source="=myOwnCounter" target="vanillabpMiIndex_Ship" />
              </zeebe:ioMapping>
            </bpmn:extensionElements>
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * What the model says at a call activity about the variables of the enclosing scopes -
   * read off the DOM, so an absent attribute reads as <code>null</code> rather than as the
   * default.
   */
  private static String propagationOf(
      final BpmnModelInstance model,
      final String callActivityId) {

    return ((CallActivity) model.getModelElementById(callActivityId))
        .getSingleExtensionElement(ZeebeCalledElement.class)
        .getDomElement()
        .getAttribute("propagateAllParentVariables");

  }

  /**
   * A caller which names the process it calls by an expression, in the four shapes the
   * deployment has to tell apart: inside an iteration and multi-instance itself, statically
   * named, with the caller's context switched off, and outside any iteration at all.
   */
  private static final String NAMED_BY_AN_EXPRESSION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="FeelCaller" isExecutable="true">
          <bpmn:subProcess id="PerGroup">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=groups" inputElement="group" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:callActivity id="CallWhateverTheDataSays">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="=whichProcess" />
              </bpmn:extensionElements>
              <bpmn:multiInstanceLoopCharacteristics>
                <bpmn:extensionElements>
                  <zeebe:loopCharacteristics inputCollection="=positions" inputElement="position" />
                </bpmn:extensionElements>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:callActivity>
            <bpmn:callActivity id="CallTheOneWeKnow">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="TheOneWeKnow" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
            <bpmn:callActivity id="CallWithoutTheCallersContext">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="=whichProcess" propagateAllParentVariables="false" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:subProcess>
          <bpmn:callActivity id="CallOutsideAnyIteration">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="=whichProcess" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
        </bpmn:process>
        <bpmn:process id="TheOneWeKnow" isExecutable="true">
          <bpmn:serviceTask id="KnownTask">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="known" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static BpmnModelInstance model(
      final String xml) {

    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static List<String> inputsOf(
      final BpmnModelInstance model,
      final String elementId) {

    final var activity = (Activity) model.getModelElementById(elementId);
    final var ioMapping = activity.getSingleExtensionElement(ZeebeIoMapping.class);
    if (ioMapping == null) {
      return List.of();
    }
    return ioMapping
        .getInputs()
        .stream()
        .map(input -> input.getTarget()
            + "="
            + input.getSource())
        .toList();

  }

  @Test
  @DisplayName("every multi-instance element gets index, total and element mappings named after itself")
  public void mappingsAreInjected() {

    final var model = model(NESTED);

    Camunda8MultiInstance.wire(model, "MiProcess", new Camunda8MultiInstance.Registry());

    assertEquals(
        List
            .of(
                "vanillabpMiIndex_Flat==loopCounter",
                "vanillabpMiTotal_Flat==count(items)",
                "vanillabpMiElement_Flat==item"),
        inputsOf(model, "Flat"));
    assertEquals(
        List
            .of(
                "vanillabpMiIndex_Outer==loopCounter",
                "vanillabpMiTotal_Outer==count(groups)",
                "vanillabpMiElement_Outer==group"),
        inputsOf(model, "Outer"));
    assertTrue(
        inputsOf(model, "Plain").isEmpty(),
        "a task which is not multi-instance is left alone");

  }

  @Test
  @DisplayName("wiring the same model twice adds nothing - a redeployment must not change the BPMN")
  public void injectionIsIdempotent() {

    final var model = model(NESTED);
    final var registry = new Camunda8MultiInstance.Registry();

    Camunda8MultiInstance.wire(model, "MiProcess", registry);
    final var afterFirst = inputsOf(model, "Flat");
    Camunda8MultiInstance.wire(model, "MiProcess", registry);

    assertEquals(afterFirst, inputsOf(model, "Flat"));

  }

  @Test
  @DisplayName("the chain of a nested task is outermost first, and holds every iteration around it")
  public void theChainIsRecorded() {

    final var model = model(NESTED);
    final var registry = new Camunda8MultiInstance.Registry();

    Camunda8MultiInstance.wire(model, "MiProcess", registry);

    assertEquals(
        List.of("Outer", "Inner"),
        registry
            .chainOf("MiProcess", "Inner")
            .stream()
            .map(Camunda8MultiInstance.MultiInstanceElement::elementId)
            .toList(),
        "the subprocess encloses the task, so it comes first");
    assertEquals(
        List.of("Flat"),
        registry
            .chainOf("MiProcess", "Flat")
            .stream()
            .map(Camunda8MultiInstance.MultiInstanceElement::elementId)
            .toList());
    assertEquals(
        List.of("Outer"),
        registry
            .chainOf("MiProcess", "Plain")
            .stream()
            .map(Camunda8MultiInstance.MultiInstanceElement::elementId)
            .toList(),
        "a plain task inside a multi-instance subprocess still runs in that iteration");
    assertTrue(
        registry.chainOf("MiProcess", "Unknown").isEmpty());

  }

  @Test
  @DisplayName("the index the SPI reports counts from 0, although Camunda 8 counts from 1")
  public void valuesAreTranslated() {

    final var model = model(NESTED);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "MiProcess", registry);

    final var values = Camunda8MultiInstance
        .valuesOf(
            registry.chainOf("MiProcess", "Inner"),
            Map
                .of(
                    "vanillabpMiIndex_Outer", 2,
                    "vanillabpMiTotal_Outer", 2,
                    "vanillabpMiElement_Outer", "g2",
                    "vanillabpMiIndex_Inner", 1,
                    "vanillabpMiTotal_Inner", 3,
                    "vanillabpMiElement_Inner", "x"));

    assertEquals(List.of("Outer", "Inner"), List.copyOf(values.keySet()), "outermost first");
    assertEquals(1, values.get("Outer").index());
    assertEquals(2, values.get("Outer").total());
    assertEquals("g2", values.get("Outer").element());
    assertEquals(0, values.get("Inner").index());
    assertEquals(3, values.get("Inner").total());
    assertEquals("x", values.get("Inner").element());

  }

  @Test
  @DisplayName("a model deployed before this adapter knew about multi-instance reports nothing")
  public void missingVariablesAreSkipped() {

    final var model = model(NESTED);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "MiProcess", registry);

    final var values = Camunda8MultiInstance
        .valuesOf(registry.chainOf("MiProcess", "Flat"), Map.of("items", List.of("a")));

    assertTrue(values.isEmpty(), "the core then names what was supplied, which is nothing");

  }

  @Test
  @DisplayName("a process without multi-instance is not touched at all")
  public void nothingHappensWithoutMultiInstance() {

    final var model = model(
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="Plain" isExecutable="true">
                <bpmn:serviceTask id="Task">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="task" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
              </bpmn:process>
            </bpmn:definitions>
            """);
    final var registry = new Camunda8MultiInstance.Registry();

    Camunda8MultiInstance.wire(model, "Plain", registry);

    assertTrue(inputsOf(model, "Task").isEmpty());
    assertTrue(registry.chainOf("Plain", "Task").isEmpty());
    // a process which is not in the model at all is not an error either
    Camunda8MultiInstance.wire(model, "Absent", registry);

  }

  @Test
  @DisplayName("an element without an input element reports no element, and one without a collection no total")
  public void whatTheModelDoesNotSayIsNotInvented() {

    final var model = model(
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="Sparse" isExecutable="true">
                <bpmn:serviceTask id="Task">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="task" />
                  </bpmn:extensionElements>
                  <bpmn:multiInstanceLoopCharacteristics>
                    <bpmn:extensionElements>
                      <zeebe:loopCharacteristics inputCollection="=items" />
                    </bpmn:extensionElements>
                  </bpmn:multiInstanceLoopCharacteristics>
                </bpmn:serviceTask>
              </bpmn:process>
            </bpmn:definitions>
            """);
    final var registry = new Camunda8MultiInstance.Registry();

    Camunda8MultiInstance.wire(model, "Sparse", registry);

    assertEquals(
        List.of("vanillabpMiIndex_Task==loopCounter", "vanillabpMiTotal_Task==count(items)"),
        inputsOf(model, "Task"));
    assertNull(
        registry
            .chainOf("Sparse", "Task")
            .getFirst()
            .elementVariable());

    final var values = Camunda8MultiInstance
        .valuesOf(
            registry.chainOf("Sparse", "Task"),
            Map.of("vanillabpMiIndex_Task", 1, "vanillabpMiTotal_Task", 4));
    assertNull(values.get("Task").element());
    assertEquals(0, values.get("Task").index());

  }

  @Test
  @DisplayName("two elements whose IDs differ only in characters a variable name cannot hold are rejected")
  public void ambiguousElementIdsFailTheDeployment() {

    final var model = model(
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="Clash" isExecutable="true">
                <bpmn:serviceTask id="my-task">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="a" />
                  </bpmn:extensionElements>
                  <bpmn:multiInstanceLoopCharacteristics>
                    <bpmn:extensionElements>
                      <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
                    </bpmn:extensionElements>
                  </bpmn:multiInstanceLoopCharacteristics>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="my.task">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="b" />
                  </bpmn:extensionElements>
                  <bpmn:multiInstanceLoopCharacteristics>
                    <bpmn:extensionElements>
                      <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
                    </bpmn:extensionElements>
                  </bpmn:multiInstanceLoopCharacteristics>
                </bpmn:serviceTask>
              </bpmn:process>
            </bpmn:definitions>
            """);

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda8MultiInstance.wire(model, "Clash", new Camunda8MultiInstance.Registry()));

    assertTrue(exception.getMessage().contains("my-task"), exception.getMessage());
    assertTrue(exception.getMessage().contains("my.task"), exception.getMessage());
    assertTrue(exception.getMessage().contains("Rename one of them"), exception.getMessage());

  }


  /**
   * Decomposition over two levels: a call activity inside a multi-instance subprocess
   * calls a process which is itself multi-instance in one place and calls a third process
   * in another - the shapes the chain of a called process has to answer.
   */
  private static final String CALL_GRAPH = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Caller" isExecutable="true">
          <bpmn:subProcess id="Outer">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=groups" inputElement="group" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:callActivity id="CallChild">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="Child" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:subProcess>
        </bpmn:process>
        <bpmn:process id="Child" isExecutable="true">
          <bpmn:serviceTask id="ChildTask">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="child" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
          <bpmn:serviceTask id="ChildMi">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="childMi" />
            </bpmn:extensionElements>
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
          </bpmn:serviceTask>
          <bpmn:callActivity id="CallGrandChild">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="GrandChild" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
          <bpmn:callActivity id="CallWhateverTheDataSays">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="=nextProcess" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
        </bpmn:process>
        <bpmn:process id="GrandChild" isExecutable="true">
          <bpmn:serviceTask id="GrandChildTask">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="grandChild" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static List<String> elementIdsOf(
      final List<Camunda8MultiInstance.MultiInstanceElement> chain) {

    return chain
        .stream()
        .map(Camunda8MultiInstance.MultiInstanceElement::elementId)
        .toList();

  }

  /**
   * The registry of {@link #CALL_GRAPH}, wired and linked the way the deployment does it.
   */
  private static Camunda8MultiInstance.Registry linkedCallGraph() {

    final var model = model(CALL_GRAPH);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "Caller", registry);
    Camunda8MultiInstance.wire(model, "Child", registry);
    Camunda8MultiInstance.wire(model, "GrandChild", registry);
    registry.registerCall("Caller", "CallChild", "Child");
    registry.registerCall("Child", "CallGrandChild", "GrandChild");
    registry.linkCalledProcesses();
    return registry;

  }

  @Test
  @DisplayName("a task in a called process runs in the iteration its call activity sits in")
  public void theChainCrossesTheProcessBoundary() {

    final var registry = linkedCallGraph();

    assertEquals(
        List.of("Outer"),
        elementIdsOf(registry.chainOf("Child", "ChildTask")),
        "the subprocess around the call activity encloses everything the called process does");

  }

  @Test
  @DisplayName("the chain reaches through two call activities")
  public void theChainCrossesTwoBoundaries() {

    final var registry = linkedCallGraph();

    assertEquals(
        List.of("Outer"),
        elementIdsOf(registry.chainOf("GrandChild", "GrandChildTask")),
        "the call activity calling the grandchild is not multi-instance itself, so the "
            + "subprocess of the first caller is the only iteration left");

  }

  @Test
  @DisplayName("a task which is multi-instance in a called process reports both chains, outermost first")
  public void theCalledProcessKeepsItsOwnChainLast() {

    final var registry = linkedCallGraph();

    assertEquals(
        List.of("Outer", "ChildMi"),
        elementIdsOf(registry.chainOf("Child", "ChildMi")),
        "what the call site encloses comes first, the element's own iteration last");

  }

  @Test
  @DisplayName("a call activity naming its process by an expression is not part of the graph")
  public void anExpressionIsNoCallSite() {

    final var model = model(CALL_GRAPH);

    assertEquals(
        Map.of("CallGrandChild", "GrandChild"),
        Camunda8MultiInstance.calledProcessesOf(model, "Child"),
        "which process '=nextProcess' reaches is decided per instance, so deploying cannot know");
    assertEquals(
        Map.of("CallChild", "Child"),
        Camunda8MultiInstance.calledProcessesOf(model, "Caller"));

  }

  @Test
  @DisplayName("a process called from two places gets both iterations, each path in its own order")
  public void theChainIsTheUnionOverTheCallSites() {

    final var model = model(TWO_CALLERS);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "CallerA", registry);
    Camunda8MultiInstance.wire(model, "CallerB", registry);
    Camunda8MultiInstance.wire(model, "Shared", registry);
    registry.registerCall("CallerA", "CallSharedFromA", "Shared");
    registry.registerCall("CallerB", "CallSharedFromB", "Shared");
    registry.linkCalledProcesses();

    assertEquals(
        List.of("LoopA", "LoopB"),
        elementIdsOf(registry.chainOf("Shared", "SharedTask")),
        "an instance runs in one of the two, and the other one's variables are simply not in "
            + "the job, which is where valuesOf leaves it out");

  }

  @Test
  @DisplayName("a level two call sites share keeps the place the first of them gave it")
  public void aSharedLevelKeepsOnePlace() {

    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model(ONE_ID_CALLER_A), "CallerA", registry);
    Camunda8MultiInstance.wire(model(SAME_ID_CALLER_C), "CallerC", registry);
    registry.registerCall("CallerA", "CallSharedFromA", "Shared");
    registry.registerCall("CallerC", "CallSharedFromC", "Shared");
    registry.linkCalledProcesses();

    assertEquals(
        List.of("Loop"),
        elementIdsOf(registry.chainOf("Shared", "SharedTask")),
        "both callers hand over the same thing under that ID, so one entry answers for both");

  }

  @Test
  @DisplayName("two call sites whose multi-instance elements share an ID but not their shape end the boot")
  public void anAmbiguousLevelIsRejected() {

    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model(ONE_ID_CALLER_A), "CallerA", registry);
    Camunda8MultiInstance.wire(model(ONE_ID_CALLER_B), "CallerB", registry);
    registry.registerCall("CallerA", "CallSharedFromA", "Shared");
    registry.registerCall("CallerB", "CallSharedFromB", "Shared");

    final var exception = assertThrows(IllegalStateException.class, registry::linkCalledProcesses);

    assertTrue(exception.getMessage().contains("CallerA"), exception.getMessage());
    assertTrue(exception.getMessage().contains("CallerB"), exception.getMessage());
    assertTrue(exception.getMessage().contains("Loop"), exception.getMessage());
    assertTrue(exception.getMessage().contains("Rename one of the two elements"), exception.getMessage());

  }

  @Test
  @DisplayName("a process calling itself ends with the levels collected so far")
  public void aRecursiveCallGraphTerminates() {

    final var model = model(RECURSION);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "Recursive", registry);
    registry.registerCall("Recursive", "CallMyself", "Recursive");
    registry.linkCalledProcesses();

    assertEquals(
        List.of("RecursiveLoop"),
        elementIdsOf(registry.chainOf("Recursive", "RecursiveTask")),
        "every round writes the same variables, so the job carries the innermost round and "
            + "the walk stops at the process it already visited");

  }

  @Test
  @DisplayName("the model is told the caller's variables travel, but only where it says nothing")
  public void propagationIsWrittenWhereTheAttributeIsAbsent() {

    final var model = model(PROPAGATION);

    assertTrue(
        Camunda8MultiInstance.theCallersVariablesReachTheCalledProcess(model, "Caller", "SaysNothing"),
        "the attribute was absent, so what the chain relies on is written down");
    assertEquals(
        "true",
        propagationOf(model, "SaysNothing"));

    assertFalse(
        Camunda8MultiInstance.theCallersVariablesReachTheCalledProcess(model, "Caller", "SaysNo"),
        "a modeller who switched the caller's context off meant it, and nothing of the caller "
            + "reaches the called process");
    assertEquals(
        "false",
        propagationOf(model, "SaysNo"),
        "what the application modelled itself is not overwritten");

    assertTrue(
        Camunda8MultiInstance.theCallersVariablesReachTheCalledProcess(model, "Caller", "SaysYes"),
        "a modeller who asked for the context gets the same answer as one who said nothing");
    assertEquals(
        "true",
        propagationOf(model, "SaysYes"));

    assertTrue(
        Camunda8MultiInstance.theCallersVariablesReachTheCalledProcess(model, "Caller", "SaysNothing"),
        "asking again gives the same answer");
    assertEquals(
        "true",
        propagationOf(model, "SaysNothing"),
        "and produces the same model, so a redeployment does not change the BPMN");

  }

  @Test
  @DisplayName("a call activity which keeps the caller's variables out is left out of the chain")
  public void aCallActivityWhichSwitchesThePropagationOffIsNoCallSite() {

    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model(CALL_GRAPH), "Caller", registry);
    Camunda8MultiInstance.wire(model(CALL_GRAPH), "Child", registry);
    registry.linkCalledProcesses();

    assertTrue(
        registry.chainOf("Child", "ChildTask").isEmpty(),
        "without the call being registered, nothing of the caller is reported - which is what "
            + "the deployment does for a call activity whose model keeps the variables at home");

  }

  @Test
  @DisplayName("an input mapping of that name with another expression ends the boot instead of being ignored")
  public void aClashingMappingIsRejected() {

    final var model = model(CLASHING_MAPPING);

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda8MultiInstance.wire(model, "Clashing", new Camunda8MultiInstance.Registry()));

    assertTrue(exception.getMessage().contains("Ship"), exception.getMessage());
    assertTrue(exception.getMessage().contains("vanillabpMiIndex_Ship"), exception.getMessage());
    assertTrue(exception.getMessage().contains("=myOwnCounter"), exception.getMessage());
    assertTrue(exception.getMessage().contains("=loopCounter"), exception.getMessage());

  }


  /**
   * One level of a handed-down entry, as the Java client builds it out of the JSON: a
   * {@code LinkedHashMap} whose numbers arrive as {@code Integer} while they fit into 32
   * bits.
   */
  private static Map<String, Object> reportedLevel(
      final String elementId,
      final Object index,
      final Object total,
      final Object item) {

    final var level = new LinkedHashMap<String, Object>();
    level.put("element", elementId);
    level.put("index", index);
    level.put("total", total);
    level.put("item", item);
    return level;

  }

  /** One entry of the handed-down chain: a caller and its levels, outermost first. */
  private static Map<String, Object> reportedCaller(
      final String callerBpmnProcessId,
      final Object... levels) {

    final var caller = new LinkedHashMap<String, Object>();
    caller.put("process", callerBpmnProcessId);
    caller.put("levels", new ArrayList<>(List.of(levels)));
    return caller;

  }

  /**
   * A job whose only variable is the handed-down chain, the way a worker of this adapter gets
   * it: the fetch list names that variable, and the values of the caller's levels are in the
   * list rather than in variables of their own.
   */
  private static Map<String, Object> jobCarrying(
      final Object handedDown) {

    final var variables = new LinkedHashMap<String, Object>();
    variables.put("vanillabpMiParents", handedDown);
    return variables;

  }

  @Test
  @DisplayName("a call activity naming its process by an expression hands its chain down in a variable")
  public void theChainOfAnExpressionIsHandedDown() {

    final var model = model(NAMED_BY_AN_EXPRESSION);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "FeelCaller", registry);

    final var expression = Camunda8MultiInstance
        .handTheChainDown(model, "FeelCaller", "CallWhateverTheDataSays", registry);

    assertEquals(
        "=append(if is defined(vanillabpMiParents) then vanillabpMiParents else [], "
            + "{process: \"FeelCaller\", levels: ["
            + "{element: \"PerGroup\", index: vanillabpMiIndex_PerGroup, "
            + "total: vanillabpMiTotal_PerGroup, item: vanillabpMiElement_PerGroup}, "
            + "{element: \"CallWhateverTheDataSays\", index: loopCounter, "
            + "total: count(positions), item: position}]})",
        expression,
        "an enclosing level reads the variables of its own mappings, while the call activity's "
            + "own round reads what the cluster offers every multi-instance element");
    assertTrue(
        inputsOf(model, "CallWhateverTheDataSays").contains("vanillabpMiParents="
            + expression),
        "and that expression is what the deployed model carries");

  }

  @Test
  @DisplayName("a statically named call activity gets no extra variable at all")
  public void aStaticallyNamedCallActivityIsLeftAlone() {

    final var model = model(NAMED_BY_AN_EXPRESSION);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "FeelCaller", registry);

    assertNull(
        Camunda8MultiInstance.handTheChainDown(model, "FeelCaller", "CallTheOneWeKnow", registry),
        "which is not even asked for it: the deployment links the two models instead");
    assertTrue(
        inputsOf(model, "CallTheOneWeKnow").isEmpty(),
        "so such a call activity pays nothing for this");

  }

  @Test
  @DisplayName("nothing is handed down where the model keeps the caller's variables out")
  public void theCallersContextStaysSwitchedOff() {

    final var model = model(NAMED_BY_AN_EXPRESSION);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "FeelCaller", registry);

    assertNull(
        Camunda8MultiInstance
            .handTheChainDown(model, "FeelCaller", "CallWithoutTheCallersContext", registry),
        "the modeller switched the caller's context off on purpose, and an input mapping would "
            + "travel all the same and undo that");
    assertTrue(inputsOf(model, "CallWithoutTheCallersContext").isEmpty());

  }

  @Test
  @DisplayName("nothing is handed down where no iteration encloses the call")
  public void thereIsNothingToHandDownWithoutAnIteration() {

    final var model = model(NAMED_BY_AN_EXPRESSION);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "FeelCaller", registry);

    assertNull(
        Camunda8MultiInstance.handTheChainDown(model, "FeelCaller", "CallOutsideAnyIteration", registry),
        "an empty entry would be payload saying nothing");
    assertTrue(inputsOf(model, "CallOutsideAnyIteration").isEmpty());

  }

  @Test
  @DisplayName("what a caller inherited is handed down too, so a static call above an expression still counts")
  public void theInheritedLevelsTravelWithTheExpression() {

    final var model = model(CALL_GRAPH);
    final var registry = linkedCallGraph();

    final var expression = Camunda8MultiInstance
        .handTheChainDown(model, "Child", "CallWhateverTheDataSays", registry);

    assertTrue(
        expression.contains("{element: \"Outer\", index: vanillabpMiIndex_Outer"),
        "the iteration is in the caller's caller, and its variables are in reach here because "
            + "the cluster copied them into this instance: "
            + expression);
    assertTrue(
        expression.contains("{process: \"Child\""),
        "the entry names the process writing it, which is what the reader checks the aggregate "
            + "against");

  }

  @Test
  @DisplayName("a handed-down chain reports the caller's iteration like any other level")
  public void theHandedDownChainIsReported() {

    final var registry = new Camunda8MultiInstance.Registry();
    registry.registerCallByExpression("FeelCaller", "TheCalled");

    final var values = Camunda8MultiInstance
        .valuesOf(
            registry,
            "TheCalled",
            "CalledTask",
            jobCarrying(
                new ArrayList<>(
                    List
                        .of(
                            reportedCaller(
                                "FeelCaller",
                                reportedLevel("PerGroup", 2, 2, "g2"),
                                reportedLevel("CallWhateverTheDataSays", 1, 3, "p1"))))));

    assertEquals(
        List.of("PerGroup", "CallWhateverTheDataSays"),
        List.copyOf(values.keySet()),
        "outermost first, and both levels belong to another BPMN process");
    assertEquals(1, values.get("PerGroup").index(), "the SPI counts from 0, the cluster from 1");
    assertEquals(2, values.get("PerGroup").total());
    assertEquals("g2", values.get("PerGroup").element());
    assertEquals(0, values.get("CallWhateverTheDataSays").index());
    assertEquals(3, values.get("CallWhateverTheDataSays").total());
    assertEquals("p1", values.get("CallWhateverTheDataSays").element());

  }

  @Test
  @DisplayName("a number too large for an int still counts, because the client hands it over as a Long")
  public void aLongIsANumberToo() {

    final var registry = new Camunda8MultiInstance.Registry();
    registry.registerCallByExpression("FeelCaller", "TheCalled");

    final var values = Camunda8MultiInstance
        .valuesOf(
            registry,
            "TheCalled",
            "CalledTask",
            jobCarrying(
                new ArrayList<>(
                    List.of(reportedCaller("FeelCaller", reportedLevel("PerGroup", 3L, 7L, "g3"))))));

    assertEquals(2, values.get("PerGroup").index());
    assertEquals(7, values.get("PerGroup").total());

  }

  @Test
  @DisplayName("an entry naming a caller of another workflow aggregate is left out")
  public void anEntryOfAnotherAggregateIsLeftOut() {

    final var registry = new Camunda8MultiInstance.Registry();
    registry.registerCallByExpression("FeelCaller", "TheCalled");

    final var values = Camunda8MultiInstance
        .valuesOf(
            registry,
            "TheCalled",
            "CalledTask",
            jobCarrying(
                new ArrayList<>(
                    List
                        .of(
                            reportedCaller("SomebodyElse", reportedLevel("TheirLoop", 1, 1, "x")),
                            reportedCaller("FeelCaller", reportedLevel("PerGroup", 1, 1, "g1"))))));

    assertEquals(
        List.of("PerGroup"),
        List.copyOf(values.keySet()),
        "a process this adapter never wired, or one with an aggregate of its own, is not guessed "
            + "at");

  }

  @Test
  @DisplayName("a level the called process iterates itself beats the one handed down")
  public void theCalledProcessWinsOverTheCaller() {

    final var model = model(NESTED);
    final var registry = new Camunda8MultiInstance.Registry();
    Camunda8MultiInstance.wire(model, "MiProcess", registry);
    registry.registerCallByExpression("FeelCaller", "MiProcess");

    final var variables = jobCarrying(
        new ArrayList<>(List.of(reportedCaller("FeelCaller", reportedLevel("Flat", 9, 9, "theCallers")))));
    variables.put("vanillabpMiIndex_Flat", 1);
    variables.put("vanillabpMiTotal_Flat", 2);
    variables.put("vanillabpMiElement_Flat", "a");

    final var values = Camunda8MultiInstance.valuesOf(registry, "MiProcess", "Flat", variables);

    assertEquals(List.of("Flat"), List.copyOf(values.keySet()), "one level, not two");
    assertEquals(
        "a",
        values.get("Flat").element(),
        "both write the same variables, the inner scope overwrites the outer one, and the job "
            + "carries the inner values");

  }

  @Test
  @DisplayName("a chain variable the application wrote itself is read as if it were not there")
  public void aValueOfAnotherShapeIsDiscarded() {

    final var registry = new Camunda8MultiInstance.Registry();
    registry.registerCallByExpression("FeelCaller", "TheCalled");

    assertTrue(
        Camunda8MultiInstance
            .valuesOf(registry, "TheCalled", "CalledTask", jobCarrying("something of the application"))
            .isEmpty(),
        "a text is what the cluster leaves behind when append() got one, and it raises no "
            + "incident");
    assertTrue(
        Camunda8MultiInstance
            .valuesOf(registry, "TheCalled", "CalledTask", jobCarrying(42))
            .isEmpty(),
        "and nothing else is cast either");

  }

  @Test
  @DisplayName("entries of another shape are left out while the real ones still count")
  public void foreignEntriesAreLeftOut() {

    final var registry = new Camunda8MultiInstance.Registry();
    registry.registerCallByExpression("FeelCaller", "TheCalled");

    final var withoutLevels = new LinkedHashMap<String, Object>();
    withoutLevels.put("process", "FeelCaller");
    final var levelWithoutAnElement = new LinkedHashMap<String, Object>();
    levelWithoutAnElement.put("index", 1);

    final var values = Camunda8MultiInstance
        .valuesOf(
            registry,
            "TheCalled",
            "CalledTask",
            jobCarrying(
                new ArrayList<>(
                    List
                        .of(
                            "one",
                            "two",
                            withoutLevels,
                            reportedCaller("FeelCaller", levelWithoutAnElement),
                            reportedCaller("FeelCaller", reportedLevel("PerGroup", 1, 1, "g1"))))));

    assertEquals(
        List.of("PerGroup"),
        List.copyOf(values.keySet()),
        "an application appending to the variable loses nothing of what VanillaBP wrote");

  }

  @Test
  @DisplayName("a level whose index reached into nothing is not reported")
  public void aLevelWithoutAnIndexIsNotReported() {

    final var registry = new Camunda8MultiInstance.Registry();
    registry.registerCallByExpression("FeelCaller", "TheCalled");

    final var values = Camunda8MultiInstance
        .valuesOf(
            registry,
            "TheCalled",
            "CalledTask",
            jobCarrying(
                new ArrayList<>(
                    List
                        .of(
                            reportedCaller(
                                "FeelCaller",
                                reportedLevel("NeverRan", null, null, null),
                                reportedLevel("PerGroup", 1, 1, "g1"))))));

    assertEquals(
        List.of("PerGroup"),
        List.copyOf(values.keySet()),
        "a key with a null cannot say whether the model names nothing there or the expression "
            + "reached into nothing, and both mean the value is not reported");

  }

}
