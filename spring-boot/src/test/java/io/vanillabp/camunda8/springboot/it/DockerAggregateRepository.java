package io.vanillabp.camunda8.springboot.it;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link DockerAggregate}, used by the platform's JPA aggregate
 * persistence.
 */
public interface DockerAggregateRepository extends JpaRepository<DockerAggregate, Long> {

}
