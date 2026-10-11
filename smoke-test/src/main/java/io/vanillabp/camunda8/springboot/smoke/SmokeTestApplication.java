package io.vanillabp.camunda8.springboot.smoke;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * A minimal Spring Boot application. {@code Camunda8AdapterDiscoveryTest} uses it to prove
 * that the Camunda 8 adapter is found when it is configured.
 * <p>
 * It lives in a MAVEN MODULE of its own, and so does its workflow module
 * {@code smoke-app}. A workflow module is a classpath entry with
 * {@code META-INF/workflow-module}. A scenario which shares that entry with another one
 * shares its workflow module. Then every application is asked for the persistence of the
 * other scenario's aggregates, and that ends the startup.
 * <p>
 * It provides a {@link SpringDataUtil} stub, so the test does not need a real database. No
 * workflow is ever started, so the persistence is never used.
 * <p>
 * It also says who owns {@link Aggregate}. An aggregate without a persistence ends the
 * startup, because the fallback would look for a Spring Data repository. This application
 * has no reason to have one. The double below is the answer. Every method of it fails
 * loudly, so a test which starts to persist for real hears about it.
 */
@SpringBootApplication
public class SmokeTestApplication {

  @Bean
  public AggregatePersistenceAware<Object> noPersistenceForTheAggregate() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {
        // every aggregate is an Object, at the greatest inheritance distance there is. So a
        // double declared for a specific class would always win over this one
        return Object.class;
      }

      @Override
      public Object save(
          final Object aggregate) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Object getAggregateId(
          final Object aggregate) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Object loadById(
          final Object aggregateId) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Class<?> getAggregateIdType() {
        // the contract's "not determinable". This double owns the serialized form, as far
        // as it owns anything at all
        return null;
      }

    };

  }

  @Bean
  public SpringDataUtil testSpringDataUtil() {

    return new SpringDataUtil() {

      @Override
      public <O> CrudRepository<? super O, Object> getRepository(
          final O entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <O> CrudRepository<O, Object> getRepository(
          final Class<O> type) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <I> I getId(
          final Object entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public String getIdName(
          final Class<?> type) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Class<?> getIdType(
          final Class<?> type) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <O> O unproxy(
          final O entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <O> boolean isPersistedEntity(
          final Class<O> entityClass,
          final O entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

    };

  }

}
