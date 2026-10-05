package io.vanillabp.camunda8.springboot.it;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OldProcessVersionsDockerAggregateRepository extends JpaRepository<OldProcessVersionsDockerAggregate, Long> {

}
