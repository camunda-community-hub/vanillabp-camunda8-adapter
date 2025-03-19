package io.vanillabp.camunda8.deployment.mongodb;

import java.time.OffsetDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.DocumentReference;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = Deployment.COLLECTION_NAME)
public abstract class Deployment
        implements io.vanillabp.camunda8.deployment.Deployment {

    static final String COLLECTION_NAME = "CAMUNDA8_DEPLOYMENTS";

    /** the key of the deployed process which is unqiue */
    @Id
    @Field(name = "C8D_DEFINITION_KEY")
    private long definitionKey;

    /** the version of the deployed process */
    @Field(name = "C8D_VERSION")
    private int version;

    @Version
    @Field(name = "C8D_RECORD_VERSION")
    private int recordVersion;
    
    @Field(name = "C8D_PACKAGE_ID")
    private int packageId;

    @DocumentReference(
            collection = DeploymentResource.COLLECTION_NAME,
            lazy = true)
    @Field("C8D_RESOURCE")
    private DeploymentResource deployedResource;

    @Field(name = "C8D_PUBLISHED_AT")
    private OffsetDateTime publishedAt;
    
    @Field(name = "C8D_TYPE")
    private String type;

    public long getDefinitionKey() {
        return definitionKey;
    }

    public void setDefinitionKey(long definitionKey) {
        this.definitionKey = definitionKey;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getPackageId() {
        return packageId;
    }

    public void setPackageId(int packageId) {
        this.packageId = packageId;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public DeploymentResource getDeployedResource() {
        return deployedResource;
    }

    public void setDeployedResource(DeploymentResource deployedResource) {
        this.deployedResource = deployedResource;
    }

    public int getRecordVersion() {
        return recordVersion;
    }
    
    public void setRecordVersion(int recordVersion) {
        this.recordVersion = recordVersion;
    }

    @Override
    public int hashCode() {
        return (int) definitionKey % Integer.MAX_VALUE;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Deployment other)) {
            return false;
        }
        return other.getDefinitionKey() == getDefinitionKey();
    }

}
