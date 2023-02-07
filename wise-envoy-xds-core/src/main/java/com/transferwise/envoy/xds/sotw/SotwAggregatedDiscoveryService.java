package com.transferwise.envoy.xds.sotw;

import com.google.common.collect.ImmutableList;
import com.transferwise.envoy.xds.api.ClusterEventSource;
import com.transferwise.envoy.xds.api.ClientConfigProvider;
import com.transferwise.envoy.xds.CommonDiscoveryRequest;
import com.transferwise.envoy.xds.CommonDiscoveryStreamObserver;
import com.transferwise.envoy.xds.DiscoveryServiceManagerFactory;
import com.transferwise.envoy.xds.api.XdsEventListener;
import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;

public class SotwAggregatedDiscoveryService<StateUpdT, DetailsT> {
    private final ClusterEventSource<StateUpdT> clusterManager;
    private final DiscoveryServiceManagerFactory<DiscoveryRequest, DiscoveryResponse, StateUpdT, DetailsT> discoveryServiceManagerFactory;
    private final ClientConfigProvider<DetailsT> clientConfigSource;
    private final Supplier<DiscoveryServiceManagerMetrics> metricsFactory;
    private final ImmutableList<XdsEventListener<DetailsT>> listeners;

    public SotwAggregatedDiscoveryService(
        ClusterEventSource<StateUpdT> clusterManager,
        DiscoveryServiceManagerFactory<DiscoveryRequest, DiscoveryResponse, StateUpdT, DetailsT> discoveryServiceManagerFactory,
        ClientConfigProvider<DetailsT> clientConfigSource,
        ImmutableList<XdsEventListener<DetailsT>> listeners,
        Supplier<DiscoveryServiceManagerMetrics> metricsFactory
    ) {
        this.clusterManager = clusterManager;
        this.discoveryServiceManagerFactory = discoveryServiceManagerFactory;
        this.clientConfigSource = clientConfigSource;
        this.metricsFactory = metricsFactory;
        this.listeners = listeners;
    }

    public StreamObserver<DiscoveryRequest> streamAggregatedResources(StreamObserver<DiscoveryResponse> responseObserver) {
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

    private CommonDiscoveryRequest<DiscoveryRequest> convertToCommonDiscoveryRequest(DiscoveryRequest request) {
        return CommonDiscoveryRequest.<DiscoveryRequest>builder()
                .typeUrl(request.getTypeUrl())
                .errorDetail(request.hasErrorDetail() ? request.getErrorDetail() : null)
                .node(request.getNode())
                .message(request)
                .build();
    }
}
