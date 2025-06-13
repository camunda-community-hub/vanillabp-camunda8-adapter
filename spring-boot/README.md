![Draft](../readme/vanillabp-headline.png)
# Camunda 8 adapter

This is an adapter which implements the binding of the Blueprint SPI to [Camunda 8](https://docs.camunda.io/) in order to run business processes.

Camunda 8 is a BPMN engine that represents a closed system similar to a database. The engine itself uses a cloud native storage concept to enable horizontal scaling. By using the remote API BPMN can be deployed and processes started. In order to execute BPMN tasks a client has to subscribe and will be pushed if work is available. Next to the BPMN engine other optional components are required to handle user tasks or to operate the system. Those components do not access the engine's data but rather use exported data stored in Elastic Search. As it's nature suggests it fits good to mid- and high-scaled use-cases.

This adapter is aware of all the details needed to keep in mind on using Camunda 8.

To run Camunda 8 on your local computer for development purposes consider to use [docker-compose](https://github.com/camunda/camunda-platform#using-docker-compose).

## Content

1. [Usage](#usage)
    1. [Task definitions](#task-definitions)
1. [Features](#features)
    1. [Worker ID](#worker-id)
    1. [Module aware deployment](#module-aware-deployment)
    1. [SPI Binding validation](#spi-binding-validation)
    1. [Managing Camunda 8 connectivity](#managing-camunda-8-connectivity)
    1. [Logging](#logging)
1. [Multi-instance](#multi-instance)
1. [Message correlation IDs](#message-correlation-ids)
1. [Using Camunda multi-tenancy](#using-camunda-multi-tenancy) 
1. [Transaction behavior](#transaction-behavior)
1. [Workflow aggregate persistence](#workflow-aggregate-persistence)
   1. [JPA](#jpa)
   1. [MongoDB](#mongodb)
1. [Workflow aggregate serialization](#workflow-aggregate-serialization)
1. [Connector support](#connector-support)

## Usage

Just add this dependency to your project, no additional dependencies from Camunda needed:

```xml
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda8-spring-boot-adapter</artifactId>
  <version>1.0.0</version>
</dependency>
```

If you want a certain version of Zeebe client then you have to replace the transitive dependencies like this:

```xml
<dependency> <!-- community client 8.5.4 -->
  <groupId>io.camunda.spring</groupId>
  <artifactId>spring-boot-starter-camunda</artifactId>
  <version>8.5.4</version>
</dependency>
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda7-spring-boot-adapter</artifactId>
  <version>1.0.0</version>
  <exclusions>
    <exclusion> <!-- official zeebe client -->
      <groupId>io.camunda</groupId>
      <artifactId>spring-boot-starter-camunda-sdk</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

*Hint:* This adapter is compatible with the configuration of the regular Zeebe Spring Boot auto-configuration. However, some additional configuration is described in the upcoming sections.

### Task definitions

As mentioned in [VanillaBP SPI](https://github.com/vanillabp/spi-for-java?tab=readme-ov-file#wire-up-a-task) a task is
wired by a task definition which is specific to the BPMS used. On using Camunda 8 this task-definition is defined
in Camunda's modeler's property panel.

For service tasks, use the input field "Job type" in section "Task definition". Keep in mind that a Camunda 8 task-definition has to be unique
in it's Zeebe-cluster (or a tenant if enabled) and therefore maybe prefixed by a string, specific to the
process, to avoid name clashes.

![service-task](./readme/task-definition-service-task.png)

For user tasks, in section "Implementation" set type to "Job worker". Currently (February of 2025),
other implementations are not supported in VanillaBP because of missing features in Camunda 8.

In section "Form", choose type "Custom form key" and enter a key identifying the user task which
will be used as a task-definition. The value of form key is ignored by Camunda and there it is 
not necessary to use a prefix as you may need for service task's task definition. 

![user-task](./readme/task-definition-user-task.png)

It is a rare case it is necessary to use one form for two different user tasks.
In this situation you can specify the same form key and annotate the workflow service's
method handling this user task by using the activities' ids as specified
in the modeler instead of the task definition:

```java
    @WorkflowTask(id = "ProcessTask1")
    @WorkflowTask(id = "ProcessTask2")
    public void processTask(
            final DemoAggregate demo,
            @TaskId final String taskId) throws Exception {
        ...
    }
```

### Restrictions

#### Disable PostgreSQL auto-commit

On using PostgreSQL one might see the error:

```
org.postgresql.util.PSQLException: Large Objects may not be used in auto-commit mode.
```

To avoid this add this `Spring Boot` properties:

```properties
spring.datasource.hikari.auto-commit=false
spring.jpa.properties.hibernate.connection.provider_disables_autocommit=true
```

## Features

### Worker ID

When using asynchronous task processing one has to define a worker id. There is no default value to avoid bringing anything unwanted into production. On using [VanillaBP's SpringApplication](https://github.com/vanillabp/spring-boot-support#spring-boot-support) instead of `org.springframework.boot.SpringApplication` [additional support](https://github.com/vanillabp/spring-boot-support#worker-id) is available.

### Module aware deployment

To avoid interdependencies between the implementation of different use-cases packed into a single microservice the concept of [workflow modules](https://github.com/vanillabp/spring-boot-support#workflow-modules) is introduced. This adapter builds a Camunda deployment for each workflow module found in the classpath.

### SPI Binding validation

On starting the application BPMNs of all workflow modules will be wired to the SPI. This includes

1. BPMN files which are part of the current workflow module bundle (e.g. `classpath:processes/*.bpmn`)
1. BPMN files deployed as part of previous versions of the workflow module
   1. Older versions of BPMN files which are part of the current workflow module bundle
   1. BPMN files which are not part of the current workflow module bundle anymore
   
This ensures that correct wiring of all process definitions according to the SPI is done.

Since Camunda 8 does not provide BPMS meta-data (e.g. historical deployments) the adapter stores necessary meta-data in separate DB tables to ensure correct operation. If you use Liquibase you can include the provided changeset in your Liquibase master file:

```yaml
databaseChangeLog:
  - include:
      file: classpath:/io/vanillabp/camunda8/liquibase/main.yaml
```

or instead use Hibernate's `ddl-auto` feature to create those tables:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update
```

### Managing Camunda 8 connectivity

The Camunda 8 adapter is based on the [spring-zeebe](https://github.com/camunda-community-hub/spring-zeebe) client.
Therefore, all settings about Camunda 8 connectivity have to be provided as can be found in that [documentation](https://github.com/camunda-community-hub/spring-zeebe#configuring-camunda-8-connection).

However, there are a couple of settings specific to tasks:

1. `task-timeout`: The lock duration Zeebe will wait, after the task has been fetched by your implementation, 
    until Zeebe assumes your execution has crashed and the task needs to be re-fetched. Zeebe`s current
    default value is 5 minutes. Set this values in respect to the expected time to complete the task.
1. `stream-enabled`: Whether to use polling or streaming to receive new tasks. Current default value is `false` (means polling).
1. `stream-timeout`: The duration to refresh the stream's connection. Current default value is no timeout.
1. `poll-interval`: Interval to poll for new tasks, if streaming of tasks is disabled. Zeebe's current default value is 100ms.
1. `poll-request-timeout`: The request-timeout for polling Zeebe for new tasks. Zeebe`s current default value is 20 seconds.

All these values can be set for specific tasks or all tasks of a workflow or all tasks
of a workflow module. Task-specific values will override workflow's or workflow-module's values and workflow-specific
values will override workflow-module's values:

```yaml
vanillabp:
  workflow-modules:
    Demo:
      adapters:
        camunda8:
          # default to all workflows of the workflow-module `Demo`
          task-timeout: PT5M
      workflows:
        DemoWorkflow:
          adapters:
            camunda8:
              # default to all tasks of the workflow `DemoWorkflow`
              task-timeout: P10M # overrides vanillabp.workflow-modules.Demo.adapters.camunda8.task-timeout
          tasks:
            logError:
              adapters:
                camunda8:
                 # used only for the task 'logError' of the workflow `DemoWorkflow`
                 task-timeout: PT3S # overrides vanillabp.workflow-modules.Demo.workflows.DemoWorkflow.adapters.camunda8.task-timeout
```

### Logging

These attributes are set in addition to the [default logging context](https://github.com/vanillabp/spring-boot-support#logging)
(defined in class `io.vanillabp.camunda8.LoggingContext`):

* Tenant-ID - The tenant ID used accessing Camunda 8 cluster.

## Multi-instance

Since Camunda 8 is a remote engine the workflow is processed in a different runtime environment. Due to this fact the Blueprint adapter cannot do the entire binding of multi-instance context information under the hood. In the BPMN the multi-instance aspects like the input element, the element's index and the total number of elements have to be defined according to a certain naming convention:

1. The input element: The ID of the BPMN element (e.g. `Activity_RequestRide`)
1. The current element's index: The ID of the BPMN element plus `_index` (e.g. `Activity_RequestRide_index`)
1. The total number of elements: The ID of the BPMN element plus `_total` (e.g. `Activity_RequestRide_total`)

Last two one needs to be defined as input mappings where the index always points to `=loopCounter` and the total number of elements can be determined using an expression like `=count(nearbyDrivers)` where "nearbyDrivers" is the collection used as a basis for multi-instance iteration.

This naming convention might look a little bit strange but is necessary to hide the BPMS in the business code. Unfortunately, since Camunda 8 is a remote engine, BPMNs get a little bit verbose as information needed to process the BPMN cannot be passed on-the-fly/at runtime and have to be provided upfront.

## Message correlation IDs

On using receive tasks Camunda 8 requires us to define correlation IDs. If your process does not have any special correlation strategy then use the expression `=id` and use the two-parameter method `ProcessService#correlateMessage` to correlate incoming messages.

In case your model has more than one receive task active you have to define unique correlation IDs for each receive task of that message name to enable the BPMS to find the right receive task to correlate to. This might happen for multi-instance receive tasks or receive tasks within a multi-instance embedded sub-process. In that case use the workflow id in combination with the multi-instance element as a correlation id: `=id+"+"+RequestRideOffer` (where "RequestRideOffer" is the name of the multi-instance element).

## Using Camunda multi-tenancy

Typically, on operating multiple workflow modules, one wants to avoid name clashes in Camunda (e.g. of event names, etc.).
Therefore, each workflow module is deployed to Camunda as a separate tenant using the workflow module's id as the tenant-id.

This behavior can be adapted.

**If you wish to define a custom tenant-id instead:**

```yaml
vanillabp:
  workflow-modules:
    ride:
      adapters:
        camunda8:
          tenant-id: taxiride
```

**If you want to disable multi-tenancy:**

```yaml
vanillabp:
  workflow-modules:
    ride:
      adapters:
        camunda8:
          use-tenants: false
```

## Transaction behavior

Since Camunda 8 is an external system to your services one has to deal with eventual consistency in conjunction with transactions. This adapter uses the recommended pattern to report a task as completed and roll back the local transaction in case of errors. Possible errors are:

1. Camunda 8 is not reachable / cannot process the confirmation of a completed task
1. The task to be completed was cancelled meanwhile e.g. due to boundary events

In order to activate this behavior one has to mark methods accessing VanillaBP-APIs as `@Transactional`, either by
using the method-level annotation:

```java
@Service
@WorkflowService(workflowAggregateClass = Ride.class)
public class TaxiRide {
   @Autowired
   private ProcessService<Ride> processService;

   @Transactional
   public void receivePayment(...) {
      ...
      processService.startWorkflow(ride);
      ...
   }

   @Transactional
   @WorkflowTask
   public void chargeCreditCard(final Ride ride) {
      ...
   }

   @Transactional
   public void paymentReceived(final Ride ride) {
      ...
      processService.correlateMessage(ride, 'PaymentReceived');
      ...
   }
}
```

or the class-level annotation:

```java
@Service
@WorkflowService(workflowAggregateClass = Ride.class)
@Transactional
public class TaxiRide {
   ...
}
```

If there is an exception in your business code and you have to roll back the transaction then Camunda's task retry-mechanism should retry as configured. Additionally, the `TaskException` is used for expected business errors handled by BPMN error boundary events which must not cause a rollback. This is handled by the adapter, one does not need to take care about it.

## Data persistence

The Camunda 8 adapter needs information about BPMN files deployed in the past. This is currently
not provided by the GRPC-API of Zeebe. Therefore, the adapter persists information by using
the same persistence technology as the application itself.

*Hint:* When moving to Camunda 8 REST API currently under development, it is likely to get
rid of this BPMN persistence.

### JPA

JPA is the default persistence. When not using Hibernate's DDL auto-update (not recommended for production) like this 

```yaml
spring:
  datasource:
    jpa:
      hibernate:
        ddl-auto: update
```

the tables required need to be created and maintained using Liquibase:

```yaml
databaseChangeLog:
  - include:
      file: classpath:io/vanillabp/camunda8/liquibase/main.yaml
```

### MongoDB

When using [MongoDB for persistence](https://github.com/vanillabp/spring-boot-support#mongodb)
any kind of OffsetDateTime converters need to be configured which are used by the Camunda 8 adapter
(and also might be used by the business application):

```java
@Configuration
public class MongoDbSpringDataUtilConfiguration {
    
    // for additional bean definition required see
    // https://github.com/vanillabp/spring-boot-support#mongodb

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(
                List.of(new OffsetDateTimeWriteConverter(),
                        new OffsetDateTimeReadConverter()));
    }
}

public class OffsetDateTimeReadConverter implements Converter<Date, OffsetDateTime> {
    @Override
    public OffsetDateTime convert(final Date source) {
        // MongoDB stores times in UTC by default
        return ZonedDateTime
                .ofInstant(source.toInstant(), ZoneOffset.UTC)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toOffsetDateTime();
    }
}

public class OffsetDateTimeWriteConverter implements Converter<OffsetDateTime, Date>  {
    @Override
    public Date convert(final OffsetDateTime source) {
        return Date.from(source.toInstant());
    }
}
```

## Workflow aggregate serialization

On using C7 one can use workflow aggregates having relations and calculated values:

```java
@Entity
public class MyAwesomeAggregate {
  ...
  @ManyToMany
  private List<MyAwesomeOtherEntity> others;
  
  @Column(name = "MY_VALUE")
  private float myValue;
  ...
  public boolean isValidValue() {
      return getMyValue() > 1.0f;
  }
}
```

Since the aggregate is attached to the underlying persistence (e.g. JPA/Hibernate) any lazy loaded collections or calculated values based on other properties can be used in BPMN expressions e.g. at conditional flows: `${validValue}`.

In C8 the workflow is processed in a different runtime environment than the business processing software using VanillaBP. So lazy loading won't work anymore. Therefore, after processing a task using a method annotated by `@WorkflowTask` **the entire aggregate is serialized every time into a JSON object and passed to C8** as a data context. This means that lazy loaded collections are read from the database and calculated values are determined.

So, when designing your aggregate you should have this in mind:
1. One solution to keep your aggregate simple is to not use relations but rather store reference ids if the referred object is not needed in BPMN expressions.
1. Another approach is to mark certain properties as transient by adding this annotation: `@com.fasterxml.jackson.annotation.JsonIgnore`. As you can see this annotation belongs to the serialization framework used by C8 and therefore may change in the future.
1. If you need a value based on a relation you may mark it as transient and add an additional getter to make this value available to expressions:
```java
public List<String> getOthersNames() {
    return getOthers()
            .stream()
            .map(MyAwesomeOtherEntity::getName)
            .collect(Collectors.toList());
}
```

*Hint:* If you see an error like this `io.grpc.StatusRuntimeException: UNKNOWN: Command 'PUBLISH' rejected with code EXCEEDED_BATCH_RECORD_SIZE`
than you know that you already hit the maximum size of data passed to Camunda 8.

## Connector support
Connectors are not supported by VanillaBP's default configuration. A connector element in a bpmn file will result in an error during the application start up:
`No public method annotated with @io.vanillabp.spi.service.WorkflowTask is matching task having task-definition 'task-definition' of process 'process-name'.`

To enable connector support `allow-connectors` must be added in the application configuration. The setting can be set globally, on workflow-module or on process level:
```yaml
vanillabp:
   allow-connectors: true
   workflow-modules:
      loan-approval:
         allow-connectors: true
```