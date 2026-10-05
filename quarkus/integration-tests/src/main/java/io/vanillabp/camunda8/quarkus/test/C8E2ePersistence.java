package io.vanillabp.camunda8.quarkus.test;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link C8E2eAggregate} - saves within the caller's JTA transaction, which
 * is the transaction a task handler runs in.
 */
@ApplicationScoped
public class C8E2ePersistence implements AggregatePersistenceAware<C8E2eAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<C8E2eAggregate> getAggregateClass() {

    return C8E2eAggregate.class;

  }

  @Override
  public C8E2eAggregate save(
      final C8E2eAggregate aggregate) {

    if (getAggregateId(aggregate) == null) {
      entityManager.persist(aggregate);
      entityManager.flush(); // assign the generated id (it identifies the workflow)
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  /**
   * Named explicitly: a workflow the BPMS starts on its own is built by the platform,
   * which needs the id property's name to hand the aggregate its identity - and a
   * hand-written persistence has nothing to derive it from.
   *
   * @return The name of the aggregate's id property
   */
  @Override
  public String getAggregateIdName() {

    return "id";

  }

  @Override
  public Object getAggregateId(
      final C8E2eAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public C8E2eAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(C8E2eAggregate.class, aggregateId);

  }

}
