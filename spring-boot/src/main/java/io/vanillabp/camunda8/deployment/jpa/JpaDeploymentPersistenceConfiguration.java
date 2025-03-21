package io.vanillabp.camunda8.deployment.jpa;

import io.vanillabp.camunda8.deployment.DeploymentPersistence;
import io.vanillabp.springboot.utils.JpaSpringDataUtilConfiguration;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/**
 * The annotation @EnableJpaRepositories cannot be used here because when
 * placing in an auto-configuration class it disables auto-scanning
 * for the entire application. If an application does not use
 * @EnableJpaRepositories but we would here then the application's
 * repositories would not be found.
 * <p>
 * Therefor JPA repositories have to be added programmatically by
 * this configuration.
 */
@Configuration
@AutoConfigureAfter({ JpaSpringDataUtilConfiguration.class, JpaRepositoriesAutoConfiguration.class })
@ConditionalOnBean(name = JpaSpringDataUtilConfiguration.BEANNAME_SPRINGDATAUTIL)
public class JpaDeploymentPersistenceConfiguration {

    @Bean(DeployedBpmnRepository.BEAN_NAME)
    @ConditionalOnMissingBean(DeployedBpmnRepository.class)
    public DeployedBpmnRepository camunda8JpaDeployedBpmnRepositoryRepository(
            final EntityManager entityManager) {

        JpaRepositoryFactory jpaRepositoryFactory = new JpaRepositoryFactory(entityManager);
        return jpaRepositoryFactory.getRepository(DeployedBpmnRepository.class);

    }

    @Bean(DeploymentResourceRepository.BEAN_NAME)
    @ConditionalOnMissingBean(DeploymentResourceRepository.class)
    public DeploymentResourceRepository camunda8JpaDeploymentResourceRepository(
            final EntityManager entityManager) {

        JpaRepositoryFactory jpaRepositoryFactory = new JpaRepositoryFactory(entityManager);
        return jpaRepositoryFactory.getRepository(DeploymentResourceRepository.class);

    }

    @Bean(DeploymentRepository.BEAN_NAME)
    @ConditionalOnMissingBean(DeploymentRepository.class)
    public DeploymentRepository camunda8JpaDeploymentRepository(
            final EntityManager entityManager) {

        JpaRepositoryFactory jpaRepositoryFactory = new JpaRepositoryFactory(entityManager);
        return jpaRepositoryFactory.getRepository(DeploymentRepository.class);

    }

    @Bean
    public DeploymentPersistence camunda8DeploymentPersistence(
            final DeploymentResourceRepository deploymentResourceRepository,
            final DeploymentRepository deploymentRepository,
            final DeployedBpmnRepository deployedBpmnRepository) {

        return new JpaDeploymentPersistence(
                deploymentResourceRepository,
                deploymentRepository,
                deployedBpmnRepository);

    }

}
