package io.vanillabp.camunda8.deployment;

import java.util.List;

import javax.persistence.Column;
import javax.persistence.DiscriminatorColumn;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.Lob;
import javax.persistence.OneToMany;
import javax.persistence.Table;

@Entity
@Table(name = "CAMUNDA8_RESOURCES")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "TYPE")
public abstract class DeploymentResource {

    @Id
    @Column(name = "ID")
    private int fileId;

    @Column(name = "RESOURCE_NAME")
    private String resourceName;

    @OneToMany(mappedBy = "deployedResource", fetch = FetchType.LAZY)
    private List<Deployment> deployments;

    @Lob
    @Column(name = "RESOURCE")
    private byte[] resource;
    
    @Column(name = "TYPE", updatable = false, insertable = false)
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
    
}
