package io.vanillabp.camunda8.springboot.listeners;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the test which completes a user task the cluster is still
 * creating. It carries nothing but its id: what the application was told is kept in the
 * static maps of {@link StillCreatingDockerWorkflowService}, for the reason written on
 * {@link UserTaskProbeDockerAggregate}.
 */
@Entity
@Table(name = "C8_STILL_CREATING_AGGREGATE")
@Getter
@Setter
public class StillCreatingDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

}
