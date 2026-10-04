package io.vanillabp.camunda8.analysis895;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The aggregates of analysis 895.
 */
public interface ReadModelAggregateRepository extends JpaRepository<ReadModelAggregate, Long> {

  /**
   * @param stages The stages to find
   * @return Every aggregate in one of them
   */
  List<ReadModelAggregate> findByStageIn(
      List<String> stages);

}
