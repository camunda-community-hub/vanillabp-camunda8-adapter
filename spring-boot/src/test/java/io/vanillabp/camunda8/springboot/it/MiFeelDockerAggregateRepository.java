package io.vanillabp.camunda8.springboot.it;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository of the workflow aggregate of the test about a process called by an expression.
 */
public interface MiFeelDockerAggregateRepository extends JpaRepository<MiFeelDockerAggregate, Long> {
}
