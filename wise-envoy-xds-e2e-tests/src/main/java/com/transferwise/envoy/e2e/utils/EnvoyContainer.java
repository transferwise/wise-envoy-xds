package com.transferwise.envoy.e2e.utils;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class EnvoyContainer extends GenericContainer<EnvoyContainer> {

    public EnvoyContainer(String image) {
        super(DockerImageName.parse(image));
    }
}
