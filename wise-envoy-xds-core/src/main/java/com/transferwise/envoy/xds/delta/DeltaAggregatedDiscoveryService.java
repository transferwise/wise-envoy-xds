package com.transferwise.envoy.xds.delta;

import com.google.common.collect.ImmutableList;
import com.transferwise.envoy.xds.api.ClusterEventSource;
import com.transferwise.envoy.xds.api.ClientConfigProvider;
import com.transferwise.envoy.xds.CommonDiscoveryRequest;
import com.transferwise.envoy.xds.CommonDiscoveryStreamObserver;
import com.transferwise.envoy.xds.DiscoveryServiceManagerFactory;
import com.transferwise.envoy.xds.api.XdsEventListener;
import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;

public class DeltaAggregatedDiscoveryService<StateUpdT, DetailsT> {
    private final ClusterEventSource<StateUpdT> clusterManager;
    private final DiscoveryServiceManagerFactory<DeltaDiscoveryRequest, DeltaDiscoveryResponse, StateUpdT, DetailsT> discoveryServiceManagerFactory;
    private final ClientConfigProvider<DetailsT> clientConfigSource;
    private final Supplier<DiscoveryServiceManagerMetrics> metricsFactory;
    private final ImmutableList<XdsEventListener<DetailsT>> listeners;

    public DeltaAggregatedDiscoveryService(ClusterEventSource<StateUpdT> clusterManager,
            DiscoveryServiceManagerFactory<DeltaDiscoveryRequest, DeltaDiscoveryResponse, StateUpdT, DetailsT> discoveryServiceManagerFactory,
            ClientConfigProvider<DetailsT> clientConfigSource,
            ImmutableList<XdsEventListener<DetailsT>> listeners,
            Supplier<DiscoveryServiceManagerMetrics> metricsFactory) {
        this.clusterManager = clusterManager;
        this.discoveryServiceManagerFactory = discoveryServiceManagerFactory;
        this.clientConfigSource = clientConfigSource;
        this.metricsFactory = metricsFactory;
        this.listeners = listeners;
    }

    public StreamObserver<DeltaDiscoveryRequest> streamDeltaAggregatedResources(StreamObserver<DeltaDiscoveryResponse> responseObserver) {
        return new CommonDiscoveryStreamObserver<>(
            responseObserver,
            clusterManager,
            discoveryServiceManagerFactory,
            this::convertToCommonDiscoveryRequest,
            clientConfigSource,
            listeners,
            metricsFactory.get()
        );
    }

    private CommonDiscoveryRequest<DeltaDiscoveryRequest> convertToCommonDiscoveryRequest(DeltaDiscoveryRequest request) {
        return CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
                .typeUrl(request.getTypeUrl())
                .errorDetail(request.hasErrorDetail() ? request.getErrorDetail() : null)
                .node(request.getNode())
                .message(request)
                .build();
    }
}
