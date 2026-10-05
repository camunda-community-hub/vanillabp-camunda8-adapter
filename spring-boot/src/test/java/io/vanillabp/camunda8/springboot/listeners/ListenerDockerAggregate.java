package io.vanillabp.camunda8.springboot.listeners;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the modelled-listener integration test. Each flag is set by one
 * method, so the test can tell from the database which element reached the application: the
 * task, its two listeners, the listener of the end event and the task the gateway chose.
 * <p>
 * Every flag reaches the database whatever the cluster does with it, because VanillaBP saves the
 * aggregate in the application's own transaction. Which of them the cluster also sees is the
 * other half of the test: {@code theWorkWasAudited} is written by an execution listener on
 * {@code end}, whose completion carries the shared values, so the gateway behind the task
 * decides on it.
 */
@Entity
@Table(name = "C8_LISTENER_AGGREGATE")
@Getter
@Setter
public class ListenerDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private boolean theWorkWasDone;

  private boolean theWorkWasAudited;

  private boolean theOrderWasArchived;

  private boolean theWorkWasPrepared;

  private boolean theProcessSawTheAudit;

  private boolean theProcessMissedTheAudit;

}
