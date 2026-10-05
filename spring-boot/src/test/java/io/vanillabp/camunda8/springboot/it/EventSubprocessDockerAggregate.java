package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the event-subprocess integration test.
 */
@Entity
@Table(name = "C8_EVENT_SUBPROCESS_AGGREGATE")
@Getter
@Setter
public class EventSubprocessDockerAggregate {

  @Id
  private String id;

  private String processedBy;

}
