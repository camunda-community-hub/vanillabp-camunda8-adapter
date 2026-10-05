package io.vanillabp.camunda8.springboot.connectors;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorDockerAggregateRepository extends JpaRepository<ConnectorDockerAggregate, Long> {
}
