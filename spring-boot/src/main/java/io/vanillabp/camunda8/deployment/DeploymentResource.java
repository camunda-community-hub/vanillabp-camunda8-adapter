package io.vanillabp.camunda8.deployment;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.List;

@Entity
@Table(name = "CAMUNDA8_RESOURCES")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "C8R_TYPE")
public abstract class DeploymentResource {

    @Id
    @Column(name = "C8R_ID")
    private int fileId;

    @Version
    @Column(name = "C8R_RECORD_VERSION")
    private int recordVersion;
    
    @Column(name = "C8R_RESOURCE_NAME")
    private String resourceName;

    @OneToMany(mappedBy = "deployedResource", fetch = FetchType.LAZY)
    private List<Deployment> deployments;

    @Lob
    @Column(name = "C8R_RESOURCE")
    private byte[] resource;
    
    @Column(name = "C8R_TYPE", updatable = false, insertable = false)
    private String type;

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
