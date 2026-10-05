package io.vanillabp.camunda8.springboot.election;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ElectionAggregateRepository extends JpaRepository<ElectionAggregate, Long> {

}
