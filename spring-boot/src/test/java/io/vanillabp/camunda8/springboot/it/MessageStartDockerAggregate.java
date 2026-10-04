package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the process which starts on a message. It has a workflow
 * service of its own, because <code>startWorkflowByMessage</code> starts the process of
 * its own process service and no other.
 */
@Entity
@Table(name = "C8_MESSAGE_START_AGGREGATE")
@Getter
@Setter
public class MessageStartDockerAggregate {

  @Id
  private String id;

  private String results;

}
