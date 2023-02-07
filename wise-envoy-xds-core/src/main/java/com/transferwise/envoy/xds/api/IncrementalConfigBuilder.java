package com.transferwise.envoy.xds.api;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static java.util.stream.Collector.Characteristics.UNORDERED;

/**
 * Builds envoy Resources in response to state changes.
 * Since we are delta-first, this is expected to return only changed resources (added, updated, removed.) It does not need to return unchanged resources (but there is no harm, just overhead, from doing so.)
 * We care a lot about preserving make-before-break behaviour, which means we have to sequence updates correctly. For a given state change the ConfigBuilder is called twice, once in add ("make") order, once in remove ("break") order.
 * You should return changes that add new stuff in add order, and changes that remove things (resources, routes, etc) in remove order.  When considering what's add and what's remove, remember that some resource changes are effectively a remove.
 *
 * @param <ResourceT> Type of envoy api resource being returned
 * @param <StateUpdT> State update type. It must be possible to infer the current full state of the world from a state update!
 * @param <DetailsT> Client details type as returned by the ClientConfigProvider
 */
public interface IncrementalConfigBuilder<ResourceT extends Message, StateUpdT, DetailsT> {

    /**
     * Retrieve the resources to be sent to envoy while processing add order.
     * This should not make changes to resources that would cause a break.
     * Ideally it should only return changed resources. This is used when deciding what updates to send out on a network change.
     * @param diff State update to apply
     * @param resourceInSubListChange Will return true for resources envoy has subscribed to (note that this is the name of the envoy resource.)
     * @param clientDetails Whatever details you provided from your ClientConfigProvider
     * @return Response containing messages to send to envoy, and named resources to remove from envoy.
     */
    Response<ResourceT> addOrder(StateUpdT diff, Predicate<String> resourceInSubListChange, DetailsT clientDetails);

    /**
     * Retrieve the resources to be sent to envoy while processing remove order.
     * Ideally it should only return changed resources. This is used when deciding what updates to send out on a network change.
     * @param diff State update to apply
     * @param resourceInSubListChange Will return true for resources envoy has subscribed to (note that this is the name of the envoy resource.)
     * @param clientDetails Whatever details you provided from your ClientConfigProvider
     * @return Response containing messages to send to envoy, and named resources to remove from envoy.
     */
    Response<ResourceT> removeOrder(StateUpdT diff, Predicate<String> resourceInSubListChange, DetailsT clientDetails);

    /**
     * Retrieve the full state of the world given a state update in add order.
     * This is used when envoy subscribes to a new resource and so we have to send it the current state.
     * @param services The last state update.
     * @param resourceInSubListChange Will return true for resources envoy has subscribed to (note that this is the name of the envoy resource.)
     * @param clientDetails Whatever details you provided from your ClientConfigProvider
     * @return Resources representing the full state of the world
     */
    Resources<ResourceT> getResourcesAddOrder(StateUpdT services, Predicate<String> resourceInSubListChange, DetailsT clientDetails);

    /**
     * Retrieve the full state of the world given a state update in remove order.
     * This is used when envoy subscribes to a new resource and so we have to send it the current state.
     * @param services The last state update.
     * @param resourceInSubListChange Will return true for resources envoy has subscribed to (note that this is the name of the envoy resource.)
     * @param clientDetails Whatever details you provided from your ClientConfigProvider
     * @return Resources representing the full state of the world
     */
    Resources<ResourceT> getResourcesRemoveOrder(StateUpdT services, Predicate<String> resourceInSubListChange, DetailsT clientDetails);

    /**
     * What xDS type does this config builder return resources for.
     * @return class of resource this config builder builds.
     */
    Class<ResourceT> handlesType();

    /**
     * Holder for a resource message, and the envoy name of that resource.
     *
     * @param <ResourceT> Type of envoy resource being held
     */
    @Value
    class NamedMessage<ResourceT extends Message> {
        String name;
        ResourceT message;

        public static NamedMessage<ClusterLoadAssignment> of(ClusterLoadAssignment cla) {
            return new NamedMessage<>(cla.getClusterName(), cla);
        }

        public static NamedMessage<Cluster> of(Cluster cla) {
            return new NamedMessage<>(cla.getName(), cla);
        }

        public static NamedMessage<Listener> of(Listener cla) {
            return new NamedMessage<>(cla.getName(), cla);
        }

        public static NamedMessage<RouteConfiguration> of(RouteConfiguration cla) {
            return new NamedMessage<>(cla.getName(), cla);
        }

    }

    @Value
    @Builder
    class Response<ResourceT extends Message> {

        @Singular
        List<NamedMessage<ResourceT>> addAndUpdates;
        @Singular
        List<String> removes;

        public static <ResourceT extends Message> Collector<Response<ResourceT>, Response.ResponseBuilder<ResourceT>, Response<ResourceT>> merge() {
            return new Collector<>() {
                @Override
                public Supplier<ResponseBuilder<ResourceT>> supplier() {
                    return Response::builder;
                }

                @Override
                public BiConsumer<ResponseBuilder<ResourceT>, Response<ResourceT>> accumulator() {
                    return (builder, res) -> {
                        res.getAddAndUpdates().forEach(builder::addAndUpdate);
                        res.getRemoves().forEach(builder::remove);
                    };
                }

                @Override
                public BinaryOperator<ResponseBuilder<ResourceT>> combiner() {
                    return (left, right) -> {
                        right.addAndUpdates.forEach(left::addAndUpdate);
                        right.removes.forEach(left::remove);
                        return left;
                    };
                }

                @Override
                public Function<ResponseBuilder<ResourceT>, Response<ResourceT>> finisher() {
                    return ResponseBuilder::build;
                }

                @Override
                public Set<Characteristics> characteristics() {
                    return Set.of(UNORDERED);
                }
            };
        }

        /**
         * Does this response actually make any changes.
         * @return true if this response is a NOOP.
         */
        public boolean isNoop() {
            return addAndUpdates.isEmpty() && removes.isEmpty();
        }
    }

    @Value
    @Builder
    class Resources<ResourceT extends Message> {
        @Singular
        List<NamedMessage<ResourceT>> resources;

        public static <ResourceT extends Message> Collector<Resources<ResourceT>, Resources.ResourcesBuilder<ResourceT>, Resources<ResourceT>> merge() {
            return new Collector<>() {
                @Override
                public Supplier<ResourcesBuilder<ResourceT>> supplier() {
                    return Resources::builder;
                }

                @Override
                public BiConsumer<ResourcesBuilder<ResourceT>, Resources<ResourceT>> accumulator() {
                    return (builder, res) -> res.getResources().forEach(builder::resource);
                }

                @Override
                public BinaryOperator<ResourcesBuilder<ResourceT>> combiner() {
                    return (left, right) -> {
                        right.resources.forEach(left::resource);
                        return left;
                    };
                }

                @Override
                public Function<ResourcesBuilder<ResourceT>, Resources<ResourceT>> finisher() {
                    return ResourcesBuilder::build;
                }

                @Override
                public Set<Characteristics> characteristics() {
                    return Set.of(UNORDERED);
                }
            };
        }

        @VisibleForTesting
        public ResourceT getByName(String name) {
            return resources.stream().filter(r -> name.equals(r.getName())).findAny().map(NamedMessage::getMessage).orElse(null);
        }
    }

}
