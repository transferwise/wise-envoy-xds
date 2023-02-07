package com.transferwise.envoy.example.configbuilder;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.transferwise.envoy.example.config.ClientConfig;
import com.transferwise.envoy.example.state.Service;
import com.transferwise.envoy.example.state.SimpleUpdate;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ClusterLoadAssignmentConfigBuilder extends AbstractConfigBuilder<ClusterLoadAssignment> {

    @Override
    public Response<ClusterLoadAssignment> addOrder(SimpleUpdate diff, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        Preconditions.checkArgument(clientDetails != null);

        // We're going to assume that endpoints should not be removed in add order, and we have to combine old and new together.
        // This may or may not make any sense, but it provides a more complex example.

        Response.ResponseBuilder<ClusterLoadAssignment> responseBuilder = Response.builder();

        for (Map.Entry<String, Service> entry : diff.getAfter().entrySet()) {
            if (!resourceInSubListChange.test(entry.getKey())) {
                // This simple example only produces 1 resource per service, named after the service, so this check is easy.
                continue;
            }

            Service before = diff.getBefore().get(entry.getKey());
            Service after = entry.getValue();

            if (before == null) {
                // Cluster is being added
                responseBuilder.addAndUpdate(clusterLoadAssignmentForService(entry.getValue(), clientDetails));
                continue;
            }

            if (!before.getEndpoints().containsAll(after.getEndpoints())) {
                // Endpoints are being added, combine the two sets.
                ImmutableSet<HostAndPort> combinedEndpoints = ImmutableSet.<HostAndPort>builder()
                    .addAll(after.getEndpoints())
                    .addAll(before.getEndpoints())
                    .build();

                Service combinedService = Service.builder()
                    .name(entry.getKey())
                    .endpoints(combinedEndpoints)
                    .build();

                responseBuilder.addAndUpdate(clusterLoadAssignmentForService(combinedService, clientDetails));
            }
        }

        return responseBuilder.build();
    }

    @Override
    public Response<ClusterLoadAssignment> removeOrder(SimpleUpdate diff, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        Preconditions.checkArgument(clientDetails != null);

        // We're going to assume that all endpoint removals happen in remove order.
        Response.ResponseBuilder<ClusterLoadAssignment> responseBuilder = Response.builder();

        for (Map.Entry<String, Service> entry : diff.getBefore().entrySet()) {

            if (!resourceInSubListChange.test(entry.getKey())) {
                // This simple example only produces 1 resource per service, named after the service, so this check is easy.
                continue;
            }

            Service before = entry.getValue();
            Service after = diff.getAfter().get(entry.getKey());

            if (after == null) {
                // Cluster is being removed entirely
                responseBuilder.remove(entry.getKey());
                continue;
            }

            if (!after.getEndpoints().containsAll(before.getEndpoints())) {
                // Some endpoints got removed, we need to inform envoy since in add order we sent the union of old and new.
                responseBuilder.addAndUpdate(clusterLoadAssignmentForService(after, clientDetails));
            }
        }

        return responseBuilder.build();
    }

    private NamedMessage<ClusterLoadAssignment> clusterLoadAssignmentForService(Service service, ClientConfig unusedClientDetails) {
        return NamedMessage.of(ClusterLoadAssignment.newBuilder()
            .setClusterName(service.getName())
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .addAllLbEndpoints(service.getEndpoints().stream().map(e -> LbEndpoint.newBuilder()
                    .setEndpoint(Endpoint.newBuilder()
                        .setAddress(Address.newBuilder()
                            .setSocketAddress(SocketAddress.newBuilder()
                                .setProtocol(SocketAddress.Protocol.TCP)
                                .setAddress(e.getHost())
                                .setPortValue(e.getPortOrDefault(80))
                                .build())
                            .build())
                        .build())
                    .build()).collect(Collectors.toSet()))
                .build())
            .build());
    }

    @Override
    public Resources<ClusterLoadAssignment> getResources(Collection<Service> services, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        Preconditions.checkArgument(clientDetails != null);
        // In this toy example the output resource name == the service name, so we can pre-apply resourceInSubListChange. This might not be true in a real service mesh, so don't assume you can do this, think about it!

        Resources.ResourcesBuilder<ClusterLoadAssignment> resourcesBuilder = Resources.builder();
        for (Service service : services) {
            if (!resourceInSubListChange.test(service.getName())) {
                // This simple example only produces 1 resource per service, named after the service, so this check is easy.
                continue;
            }

            resourcesBuilder.resource(clusterLoadAssignmentForService(service, clientDetails));
        }
        return resourcesBuilder.build();
    }

    @Override
    public Class<ClusterLoadAssignment> handlesType() {
        return ClusterLoadAssignment.class;
    }
}
