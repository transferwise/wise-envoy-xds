package com.transferwise.envoy.example.configbuilder;

import com.google.common.base.Preconditions;
import com.google.protobuf.Duration;
import com.transferwise.envoy.example.config.ClientConfig;
import com.transferwise.envoy.example.state.Service;
import com.transferwise.envoy.example.state.SimpleUpdate;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

public class ClusterConfigBuilder extends AbstractConfigBuilder<Cluster> {

    @Override
    public Response<Cluster> addOrder(SimpleUpdate diff, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        Preconditions.checkArgument(clientDetails != null);

        Response.ResponseBuilder<Cluster> responseBuilder = Response.builder();

        for (Map.Entry<String, Service> entry : diff.getAfter().entrySet()) {
            if (diff.getBefore().containsKey(entry.getKey())) {
                // In this very simple example there are no properties of a Service that can change the Clusters we generated.
                // So if Service already existed, there is nothing to do.
                continue;
            }
            if (!resourceInSubListChange.test(entry.getKey())) {
                // In this example the output resource name == the service name, and only one Cluster can be generated for each Service.
                // This means it's safe for us to pre-apply resourceInSubListChange here.
                // In our real control plane we often generate multiple named Clusters for one Service, so we have to be more careful than this!
                continue;
            }
            responseBuilder.addAndUpdate(clusterForService(entry.getValue(), clientDetails));
        }

        return responseBuilder.build();
    }

    @Override
    public Response<Cluster> removeOrder(SimpleUpdate diff, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        Preconditions.checkArgument(clientDetails != null);

        Response.ResponseBuilder<Cluster> responseBuilder = Response.builder();

        for (Map.Entry<String, Service> entry : diff.getBefore().entrySet()) {
            // Similar to above, in this simple example we only generate a single cluster per service, and it has the same name as the service. This makes removal very simple.
            if (!resourceInSubListChange.test(entry.getKey())) {
                continue;
            }
            if (!diff.getAfter().containsKey(entry.getKey())) {
                responseBuilder.remove(entry.getKey());
            }
        }

        return responseBuilder.build();
    }


    @Override
    public Resources<Cluster> getResources(Collection<Service> services, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        Preconditions.checkArgument(clientDetails != null);

        Resources.ResourcesBuilder<Cluster> resourcesBuilder = Resources.builder();

        for (Service service : services) {
            // Similar to above, in this simple example we only generate a single cluster per service, and it has the same name as the service.
            if (!resourceInSubListChange.test(service.getName())) {
                continue;
            }
            resourcesBuilder.resource(clusterForService(service, clientDetails));
        }

        return resourcesBuilder.build();
    }

    private NamedMessage<Cluster> clusterForService(Service service, ClientConfig unusedClientDetails) {
        // Build the appropriate cluster for our service for this particular client.
        // In our example this is really simple, and doesn't depend on anything from the client config.
        return NamedMessage.of(Cluster.newBuilder()
            .setName(service.getName())
            .setType(Cluster.DiscoveryType.EDS)
            .setConnectTimeout(Duration.newBuilder().setSeconds(1).build())
            .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setResourceApiVersion(ApiVersion.V3)
                        .setAds(AggregatedConfigSource.newBuilder().build())
                        .build()
                )
                .build())
            .build());
    }


    @Override
    public Class<Cluster> handlesType() {
        return Cluster.class;
    }
}
