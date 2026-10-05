package io.vanillabp.camunda8.quarkus.nativeimage;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link NativeImageAggregate} - saves within the caller's JTA
 * transaction, which is the transaction a task handler runs in.
 */
@ApplicationScoped
public class NativeImagePersistence implements AggregatePersistenceAware<NativeImageAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<NativeImageAggregate> getAggregateClass() {

    return NativeImageAggregate.class;

  }

  @Override
  public NativeImageAggregate save(
      final NativeImageAggregate aggregate) {

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
      final NativeImageAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public NativeImageAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(NativeImageAggregate.class, ((Number) aggregateId).longValue());

  }

}
