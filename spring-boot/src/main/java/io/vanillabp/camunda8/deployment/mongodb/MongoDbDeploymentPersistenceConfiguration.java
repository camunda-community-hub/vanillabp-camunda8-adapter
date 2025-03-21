package io.vanillabp.camunda8.deployment.mongodb;

import io.vanillabp.camunda8.deployment.DeploymentPersistence;
import io.vanillabp.springboot.utils.MongoDbSpringDataUtil;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

@Configuration
@AutoConfigureAfter(MongoRepositoriesAutoConfiguration.class)
@ConditionalOnBean(MongoDbSpringDataUtil.class)
public class MongoDbDeploymentPersistenceConfiguration {

    private MongoRepositoryFactory mongoRepositoryFactory;

    @Bean()
    @ConditionalOnMissingBean(io.vanillabp.camunda8.deployment.mongodb.DeployedBpmnRepository.class)
    public io.vanillabp.camunda8.deployment.mongodb.DeployedBpmnRepository camunda8MongoDbDeployedBpmnRepository(
            final MongoOperations mongoOperations) {

        if (mongoRepositoryFactory == null) {
            mongoRepositoryFactory = new MongoRepositoryFactory(mongoOperations);
        }
        return mongoRepositoryFactory.getRepository(DeployedBpmnRepository.class);

    }

    @Bean
    @ConditionalOnMissingBean(io.vanillabp.camunda8.deployment.mongodb.DeploymentResourceRepository.class)
    public io.vanillabp.camunda8.deployment.mongodb.DeploymentResourceRepository camunda8MongoDbDeploymentResourceRepository(
            final MongoOperations mongoOperations) {

        if (mongoRepositoryFactory == null) {
            mongoRepositoryFactory = new MongoRepositoryFactory(mongoOperations);
        }
        return mongoRepositoryFactory.getRepository(DeploymentResourceRepository.class);

    }

    @Bean
    @ConditionalOnMissingBean(io.vanillabp.camunda8.deployment.mongodb.DeploymentRepository.class)
    public io.vanillabp.camunda8.deployment.mongodb.DeploymentRepository c8MongoDbDeploymentRepository(
            final MongoOperations mongoOperations) {

        if (mongoRepositoryFactory == null) {
            mongoRepositoryFactory = new MongoRepositoryFactory(mongoOperations);
        }
        return mongoRepositoryFactory.getRepository(DeploymentRepository.class);

    }

    @Bean
    public DeploymentPersistence c8DeploymentPersistence(
            final DeploymentResourceRepository deploymentResourceRepository,
            final DeploymentRepository deploymentRepository,
            final DeployedBpmnRepository deployedBpmnRepository) {

        return new MongoDbDeploymentPersistence(
                deploymentResourceRepository,
                deploymentRepository,
                deployedBpmnRepository);

    }

}
