package io.vanillabp.camunda8.springboot.it;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerThreadsDockerAggregateRepository extends JpaRepository<WorkerThreadsDockerAggregate, Long> {

}
