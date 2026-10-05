package io.vanillabp.camunda8.springboot.listeners;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StillCreatingDockerAggregateRepository extends JpaRepository<StillCreatingDockerAggregate, Long> {

}
