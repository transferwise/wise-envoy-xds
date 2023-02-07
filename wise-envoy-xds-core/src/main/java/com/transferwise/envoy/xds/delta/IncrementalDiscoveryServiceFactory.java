package com.transferwise.envoy.xds.delta;

import com.google.protobuf.Message;
import com.transferwise.envoy.xds.NodeConfig;
import com.transferwise.envoy.xds.api.IncrementalConfigBuilder;
import com.transferwise.envoy.xds.DiscoveryService;
import com.transferwise.envoy.xds.DiscoveryServiceFactory;
import com.transferwise.envoy.xds.TypeUrl;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.function.Predicate;

public class IncrementalDiscoveryServiceFactory<StateUpdT, DetailsT> implements DiscoveryServiceFactory<DeltaDiscoveryRequest, DeltaDiscoveryResponse, StateUpdT, DetailsT> {

    private final List<IncrementalConfigBuilder<?, StateUpdT, DetailsT>> configBuilders;

    public IncrementalDiscoveryServiceFactory(List<IncrementalConfigBuilder<?, StateUpdT, DetailsT>> configBuilders) {
        this.configBuilders = configBuilders;

        for (IncrementalConfigBuilder<?, StateUpdT, DetailsT> icb : configBuilders) {
            if (!TypeUrl.getMessageClazzes().contains(icb.handlesType())) {
                throw new IllegalArgumentException(icb.handlesType() + " is not a known ADS message type. Valid types are: " + TypeUrl.getMessageClazzes());
            }
        }
    }

    private SubManager getSubmanagerForType(TypeUrl type, NodeConfig<DetailsT> nodeConfig) {
        if (type.isWildcard()) {
            return new WildcardSubManager(nodeConfig);
        } else {
            return new SubListSubManager(nodeConfig);
        }
    }

    private static class NoOpConfigBuilder<ResourceT extends Message, StateUpdT, DetailsT> implements IncrementalConfigBuilder<ResourceT, StateUpdT, DetailsT> {

        private final Class<ResourceT> type;

        private NoOpConfigBuilder(Class<ResourceT> type) {
            this.type = type;
        }

        @Override
        public Response<ResourceT> addOrder(StateUpdT diff, Predicate<String> resourceInSubListChange, DetailsT clientDetails) {
            return Response.<ResourceT>builder().build();
        }

        @Override
        public Response<ResourceT> removeOrder(StateUpdT diff, Predicate<String> resourceInSubListChange, DetailsT clientDetails) {
            return Response.<ResourceT>builder().build();
        }

        @Override
        public Resources<ResourceT> getResourcesAddOrder(StateUpdT services, Predicate<String> resourceInSubListChange, DetailsT clientDetails) {
            return Resources.<ResourceT>builder().build();
        }

        @Override
        public Resources<ResourceT> getResourcesRemoveOrder(StateUpdT services, Predicate<String> resourceInSubListChange, DetailsT clientDetails) {
            return Resources.<ResourceT>builder().build();
        }

        @Override
        public Class<ResourceT> handlesType() {
            return type;
        }
    }

    @SuppressWarnings("unchecked")
    public <E extends Message> IncrementalConfigBuilder<E, StateUpdT, DetailsT> getConfigBuilderForType(TypeUrl type) {
        return (IncrementalConfigBuilder<E, StateUpdT, DetailsT>) configBuilders.stream().filter(b -> type.getMessageClazz().equals(b.handlesType())).findAny().orElseGet(() -> new NoOpConfigBuilder<>(type.getMessageClazz()));
    }

    @Override
    public DiscoveryService<DeltaDiscoveryRequest, StateUpdT> createDiscoveryService(StreamObserver<DeltaDiscoveryResponse> responseObserver, NodeConfig<DetailsT> nodeConfig, TypeUrl type) {
        return new IncrementalDiscoveryService<>(type, responseObserver, getConfigBuilderForType(type), nodeConfig, getSubmanagerForType(type, nodeConfig));
    }

}
