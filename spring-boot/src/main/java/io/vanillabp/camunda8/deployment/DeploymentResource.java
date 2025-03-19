package io.vanillabp.camunda8.deployment;

import java.util.List;

public interface DeploymentResource {

    int getFileId();

    String getResourceName();

    <D extends Deployment> List<D> getDeployments();

    byte[] getResource();

}
