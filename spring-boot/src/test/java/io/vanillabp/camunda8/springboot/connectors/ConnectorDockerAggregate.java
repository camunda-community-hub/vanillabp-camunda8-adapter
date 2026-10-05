package io.vanillabp.camunda8.springboot.connectors;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the connector integration test. The flag is set by the task
 * BEHIND the connector element, so it stays false for as long as nothing serves the
 * connector.
 */
@Entity
@Table(name = "C8_CONNECTOR_AGGREGATE")
@Getter
@Setter
public class ConnectorDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private boolean pastTheConnector;

}
