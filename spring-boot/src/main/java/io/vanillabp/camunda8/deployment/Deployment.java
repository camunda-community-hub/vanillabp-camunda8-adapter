package io.vanillabp.camunda8.deployment;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "CAMUNDA8_DEPLOYMENTS")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "TYPE")
@IdClass(DeploymentId.class)
public abstract class Deployment {

    /** the key of the deployed process */
    @Id
    @Column(name = "DEFINITION_KEY")
    private long definitionKey;

    /** the version of the deployed process */
    @Id
    @Column(name = "VERSION")
    private int version;

    @Version
    @Column(name = "RECORD_VERSION")
    private int recordVersion;
    
    @Column(name = "PACKAGE_ID")
    private int packageId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "RESOURCE", nullable = false, updatable = false)
    private DeploymentResource deployedResource;

    @Column(name = "PUBLISHED_AT", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime publishedAt;
    
    @Column(name = "TYPE", updatable = false, insertable = false)
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

    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }

    public int getRecordVersion() {
        return recordVersion;
    }
    
    public void setRecordVersion(int recordVersion) {
        this.recordVersion = recordVersion;
    }
    
}
