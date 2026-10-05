package io.vanillabp.camunda8.quarkus.test.restart;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link C8RestartAggregate} - saves within the caller's JTA
 * transaction, which is the transaction a task handler runs in.
 */
@ApplicationScoped
public class C8RestartPersistence implements AggregatePersistenceAware<C8RestartAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<C8RestartAggregate> getAggregateClass() {

    return C8RestartAggregate.class;

  }

  @Override
  public C8RestartAggregate save(
      final C8RestartAggregate aggregate) {

    if (getAggregateId(aggregate) == null) {
      entityManager.persist(aggregate);
      entityManager.flush();
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  /**
   * Named explicitly: the adapter stores the aggregate's id in the cluster under the name
   * of the id property, and a hand-written persistence has nothing to derive it from.
   *
   * @return The name of the aggregate's id property
   */
  @Override
  public String getAggregateIdName() {

    return "id";

  }

  @Override
  public Object getAggregateId(
      final C8RestartAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public C8RestartAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(C8RestartAggregate.class, ((Number) aggregateId).longValue());

  }

}
