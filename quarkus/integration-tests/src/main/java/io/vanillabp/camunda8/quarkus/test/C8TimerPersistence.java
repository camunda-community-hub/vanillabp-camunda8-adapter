package io.vanillabp.camunda8.quarkus.test;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link C8TimerAggregate} - saves within the caller's JTA transaction, which
 * is the transaction a task handler runs in.
 */
@ApplicationScoped
public class C8TimerPersistence implements AggregatePersistenceAware<C8TimerAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<C8TimerAggregate> getAggregateClass() {

    return C8TimerAggregate.class;

  }

  @Override
  public C8TimerAggregate save(
      final C8TimerAggregate aggregate) {

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
      final C8TimerAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public C8TimerAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(C8TimerAggregate.class, aggregateId);

  }

}
