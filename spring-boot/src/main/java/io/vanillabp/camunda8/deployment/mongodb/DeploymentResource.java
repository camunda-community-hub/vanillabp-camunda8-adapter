package io.vanillabp.camunda8.deployment.mongodb;

import jakarta.persistence.Lob;
import jakarta.persistence.Version;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.DocumentReference;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = DeploymentResource.COLLECTION_NAME)
public abstract class DeploymentResource
        implements io.vanillabp.camunda8.deployment.DeploymentResource {

    static final String COLLECTION_NAME = "CAMUNDA8_RESOURCES";

    @org.springframework.data.annotation.Id
    @Field(name = "C8D_ID")
    private int fileId;

    @Version
    @Field(name = "C8R_RECORD_VERSION")
    private int recordVersion;
    
    @Field(name = "C8R_RESOURCE_NAME")
    private String resourceName;

    @DocumentReference(
            collection = Deployment.COLLECTION_NAME,
            lazy = true)
    private List<Deployment> deployments;

    @Lob
    @Field(name = "C8R_RESOURCE")
    private byte[] resource;

    public int getFileId() {
        return fileId;
    }

    public void setFileId(int fileId) {
        this.fileId = fileId;
    }

    public byte[] getResource() {
        return resource;
    }

    public void setResource(byte[] resource) {
        this.resource = resource;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public List<Deployment> getDeployments() {
        return deployments;
    }

    public void setDeployments(List<Deployment> deployments) {
        this.deployments = deployments;
    }

    public int getRecordVersion() {
        return recordVersion;
    }
    
    public void setRecordVersion(int recordVersion) {
        this.recordVersion = recordVersion;
    }

    @Override
    public int hashCode() {
        return getFileId();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DeploymentResource other)) {
            return false;
        }
        return getFileId() == other.getFileId();
    }

}
