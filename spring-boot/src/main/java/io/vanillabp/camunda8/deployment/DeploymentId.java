package io.vanillabp.camunda8.deployment;

import java.io.Serializable;

public class DeploymentId implements Serializable {

    private static final long serialVersionUID = 1L;

    private long definitionKey;

    private int version;

    public DeploymentId() {
        // used by JPA
    }
    
    public DeploymentId(long definitionKey, int version) {

        this.definitionKey = definitionKey;
        this.version = version;
        
    }
    
    @Override
    public int hashCode() {
        
        int hash = 7;
        hash = 31 * hash + (int) definitionKey;
        hash = 31 * hash + version;
        return hash;

    }

    @Override
    public boolean equals(Object obj) {
        
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof DeploymentId)) {
            return false;
        }
        final var other = (DeploymentId) obj;
        if (definitionKey != other.definitionKey) {
            return false;
        }
        return version == other.version;
        
    }
    
}
