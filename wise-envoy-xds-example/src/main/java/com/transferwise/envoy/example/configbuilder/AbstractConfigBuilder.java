package com.transferwise.envoy.example.configbuilder;

import com.google.protobuf.Message;
import com.transferwise.envoy.xds.api.IncrementalConfigBuilder;
import com.transferwise.envoy.example.config.ClientConfig;
import com.transferwise.envoy.example.state.Service;
import com.transferwise.envoy.example.state.SimpleUpdate;
import java.util.Collection;
import java.util.function.Predicate;

/**
 * Our simple implementation allows us to take some shortcuts generating SoTW, common to all the config builders.
 * We simply pass the servicesPre/PostRemove state into a common getResource method.
 * If your implementation does things like removing some resources due to services being added, then this might not be appropriate.
 * @param <ResourceT> Envoy DS resource type to generate configs for
 */
public abstract class AbstractConfigBuilder<ResourceT extends Message> implements
    IncrementalConfigBuilder<ResourceT, SimpleUpdate, ClientConfig> {

    @Override
    public Resources<ResourceT> getResourcesAddOrder(SimpleUpdate services, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        return getResources(services.getServicesPreRemove(), resourceInSubListChange, clientDetails);
    }

    @Override
    public Resources<ResourceT> getResourcesRemoveOrder(SimpleUpdate services, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        return getResources(services.getServicesPostRemove(), resourceInSubListChange, clientDetails);
    }

    public abstract Resources<ResourceT> getResources(Collection<Service> services, Predicate<String> resourceInSubListChange, ClientConfig clientDetails);
}
