package io.vanillabp.camunda8.springboot.refusedstart;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefusedStartAggregateRepository extends JpaRepository<RefusedStartAggregate, Long> {
}
