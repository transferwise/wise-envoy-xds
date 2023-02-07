package com.transferwise.envoy.example.configbuilder;

import com.google.common.base.Preconditions;
import com.transferwise.envoy.example.config.ClientConfig;
import com.transferwise.envoy.example.state.Service;
import com.transferwise.envoy.example.state.SimpleUpdate;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

public class RouteConfigurationConfigBuilder extends AbstractConfigBuilder<RouteConfiguration> {

    // In this simple example we have a single route config with a fixed name. It always exists.
    private static final String ROUTE_NAME = "all_routes";

    public Response<RouteConfiguration> eitherWay(SimpleUpdate diff, Predicate<String> resourceInSubListChange, ClientConfig clientDetails, boolean includeRemoved) {
        // OK, we have to build the complete route config (remember this is a single resource that mentions all clusters, not something we can incrementally update (although if you use VHDS, you can do this incrementally!))
        RouteConfiguration.Builder routeBuilder = RouteConfiguration.newBuilder()
            .setName(ROUTE_NAME);

        if (includeRemoved) {
            for (Map.Entry<String, Service> entry : diff.getBefore().entrySet()) {
                // In add order we haven't removed any clusters yet.
                if (diff.getAfter().containsKey(entry.getKey())) {
                    continue;
                }
                routeBuilder.addVirtualHosts(toVirtualHost(entry.getValue(), clientDetails));
            }
        }
        for (Map.Entry<String, Service> entry : diff.getAfter().entrySet()) {
            routeBuilder.addVirtualHosts(toVirtualHost(entry.getValue(), clientDetails));
        }

        return Response.<RouteConfiguration>builder().addAndUpdate(NamedMessage.of(routeBuilder.build())).build();
    }

    @Override
    public Response<RouteConfiguration> addOrder(SimpleUpdate diff, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        Preconditions.checkArgument(clientDetails != null);

        // Our toy example has a single route config with routes to every service. If a service is added/removed we have to update the route config to add a route to it, but we need to preserve all the existing routes too!

        if (!resourceInSubListChange.test(ROUTE_NAME)) {
            return Response.<RouteConfiguration>builder().build();
        }

        if (diff.getBefore().keySet().containsAll(diff.getAfter().keySet())) {
            // If no services got added then there are no changes.
            return Response.<RouteConfiguration>builder().build();
        }

        return eitherWay(diff, resourceInSubListChange, clientDetails, true);
    }

    @Override
    public Response<RouteConfiguration> removeOrder(SimpleUpdate diff, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        Preconditions.checkArgument(clientDetails != null);

        // Our toy example has a single route config with routes to every service. If a service is added/removed we have to update the route config to add a route to it, but we need to preserve all the existing routes too!

        if (!resourceInSubListChange.test(ROUTE_NAME)) {
            return Response.<RouteConfiguration>builder().build();
        }

        if (diff.getAfter().keySet().containsAll(diff.getBefore().keySet())) {
            // If no services got removed then there are no changes.
            return Response.<RouteConfiguration>builder().build();
        }

        return eitherWay(diff, resourceInSubListChange, clientDetails, false);
    }

    @Override
    public Resources<RouteConfiguration> getResources(Collection<Service> services, Predicate<String> resourceInSubListChange, ClientConfig clientDetails) {
        Preconditions.checkArgument(clientDetails != null);

        if (!resourceInSubListChange.test(ROUTE_NAME)) {
            return Resources.<RouteConfiguration>builder().build();
        }

        RouteConfiguration.Builder routeBuilder = RouteConfiguration.newBuilder()
            .setName(ROUTE_NAME);

        for (Service service : services) {
            routeBuilder.addVirtualHosts(toVirtualHost(service, clientDetails));
        }

        return Resources.<RouteConfiguration>builder().resource(NamedMessage.of(routeBuilder.build())).build();

    }

    private VirtualHost toVirtualHost(Service service, ClientConfig clientDetails) {
        return VirtualHost.newBuilder()
            .setName(service.getName())
            .addDomains(service.getName())
            .addDomains(service.getName() + ":" + clientDetails.getListenPort())
            .addRoutes(Route.newBuilder()
                .setMatch(RouteMatch.newBuilder()
                    .setPrefix("/")
                    .build())
                .setRoute(RouteAction.newBuilder()
                    .setCluster(service.getName())
                    .build())
                .build())
            .build();
    }

    @Override
    public Class<RouteConfiguration> handlesType() {
        return RouteConfiguration.class;
    }
}
