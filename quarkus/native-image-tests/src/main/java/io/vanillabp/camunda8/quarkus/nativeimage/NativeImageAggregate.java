package io.vanillabp.camunda8.quarkus.nativeimage;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the native-image application. Its status is how the
 * application learns that the cluster handed the job back to a handler inside the
 * binary.
 */
@Entity
@Table(name = "C8_NATIVE_AGGREGATE")
@Getter
@Setter
public class NativeImageAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String status;

}
