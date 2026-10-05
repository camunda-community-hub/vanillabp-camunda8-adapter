package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the test which lets a boundary event take one of two open tasks
 * away. What it records is what the application was told about each of them.
 */
@Entity
@Table(name = "C8_OTHER_OPEN_TASKS_AGGREGATE")
@Getter
@Setter
public class OtherOpenTasksDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * The two branches of the parallel gateway load this aggregate and save it, each writing
   * an attribute of its own. Without a version attribute the one committing last puts back
   * what it read and the other one's write is gone without any error - which is what the
   * boot warns about for this very model. With it the collision becomes an exception
   * VanillaBP reports and the cluster retries.
   */
  @Version
  private Long version;

  /**
   * The job key of the task the boundary event takes away.
   */
  private String takenAwayTaskId;

  /**
   * The job key of the task which stays open, and whose next wake-up is what runs the
   * check.
   */
  private String stayingTaskId;

  /**
   * What the application heard about the task the boundary event took away.
   */
  private String whatHappenedToTheTakenTask;

}
