package io.vanillabp.camunda8.quarkus.test;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link C8MessageStartAggregate} - saves within the caller's JTA
 * transaction, which is the transaction a task handler runs in.
 */
@ApplicationScoped
public class C8MessageStartPersistence implements AggregatePersistenceAware<C8MessageStartAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<C8MessageStartAggregate> getAggregateClass() {

    return C8MessageStartAggregate.class;

  }

  @Override
  public C8MessageStartAggregate save(
      final C8MessageStartAggregate aggregate) {

    if (entityManager.contains(aggregate)) {
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public String getAggregateIdName() {

    return "id";

  }

  @Override
  public Object getAggregateId(
      final C8MessageStartAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public C8MessageStartAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(C8MessageStartAggregate.class, aggregateId);

  }

}
