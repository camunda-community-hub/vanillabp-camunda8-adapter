package io.vanillabp.camunda8.springboot.listeners;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The aggregates of the modelled-listener integration test.
 */
@Repository
public interface ListenerDockerAggregateRepository extends JpaRepository<ListenerDockerAggregate, Long> {
}
