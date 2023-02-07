package com.transferwise.envoy.xds;

import com.google.common.collect.ImmutableList;
import com.transferwise.envoy.xds.api.ClientConfigProvider;
import com.transferwise.envoy.xds.api.ClusterEventSource;
import com.transferwise.envoy.xds.api.IncrementalConfigBuilder;
import com.transferwise.envoy.xds.api.StateBacklogFactory;
import com.transferwise.envoy.xds.api.XdsEventListener;
import com.transferwise.envoy.xds.delta.DeltaAggregatedDiscoveryService;
import com.transferwise.envoy.xds.delta.IncrementalDiscoveryServiceFactory;
import com.transferwise.envoy.xds.sotw.SotwAggregatedDiscoveryService;
import com.transferwise.envoy.xds.sotw.SotwDiscoveryServiceFactory;
import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.function.Supplier;

public class AggregatedDiscoveryService<StateUpdT, DetailsT> extends AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase {

    private final SotwAggregatedDiscoveryService<StateUpdT, DetailsT> sotwAds;
    private final DeltaAggregatedDiscoveryService<StateUpdT, DetailsT> incrementalAds;

    public AggregatedDiscoveryService(SotwAggregatedDiscoveryService<StateUpdT, DetailsT> sotwAds, DeltaAggregatedDiscoveryService<StateUpdT, DetailsT> incrementalAds) {
        this.sotwAds = sotwAds;
        this.incrementalAds = incrementalAds;
    }

    public AggregatedDiscoveryService(
        ClusterEventSource<StateUpdT> clusterManager,
        ImmutableList<IncrementalConfigBuilder<?, StateUpdT, DetailsT>> configBuilders,
        ClientConfigProvider<DetailsT> clientConfigSource,
        ImmutableList<XdsEventListener<DetailsT>> listeners,
        StateBacklogFactory<StateUpdT> stateBacklogFactory,
        Supplier<DiscoveryServiceManagerMetrics> metricsFactory
    ) {
        this(
            sotwAggregatedDiscoveryService(clusterManager, configBuilders, clientConfigSource, listeners, stateBacklogFactory, metricsFactory),
            deltaAggregatedDiscoveryService(clusterManager, configBuilders, clientConfigSource, listeners, stateBacklogFactory, metricsFactory)
        );
    }

    @Override
    public StreamObserver<DiscoveryRequest> streamAggregatedResources(StreamObserver<DiscoveryResponse> responseObserver) {
        return sotwAds.streamAggregatedResources(responseObserver);
    }

    @Override
    public StreamObserver<DeltaDiscoveryRequest> deltaAggregatedResources(StreamObserver<DeltaDiscoveryResponse> responseObserver) {
        return incrementalAds.streamDeltaAggregatedResources(responseObserver);
    }

    public static <StateUpdT, DetailsT> SotwAggregatedDiscoveryService<StateUpdT, DetailsT> sotwAggregatedDiscoveryService(
        ClusterEventSource<StateUpdT> clusterManager,
        ImmutableList<IncrementalConfigBuilder<?, StateUpdT, DetailsT>> configBuilders,
        ClientConfigProvider<DetailsT> clientConfigSource,
        ImmutableList<XdsEventListener<DetailsT>> listeners,
        StateBacklogFactory<StateUpdT> stateBacklogFactory,
        Supplier<DiscoveryServiceManagerMetrics> metricsFactory
    ) {
        var discoveryServiceManagerFactory = new DiscoveryServiceManagerFactory<>(new SotwDiscoveryServiceFactory<>(configBuilders), stateBacklogFactory);
        return new SotwAggregatedDiscoveryService<>(clusterManager, discoveryServiceManagerFactory, clientConfigSource, listeners, metricsFactory);
    }

    public static <StateUpdT, DetailsT> DeltaAggregatedDiscoveryService<StateUpdT, DetailsT> deltaAggregatedDiscoveryService(
        ClusterEventSource<StateUpdT> clusterManager,
        List<IncrementalConfigBuilder<?, StateUpdT, DetailsT>> configBuilders,
        ClientConfigProvider<DetailsT> clientConfigSource,
        ImmutableList<XdsEventListener<DetailsT>> listeners,
        StateBacklogFactory<StateUpdT> stateBacklogFactory,
        Supplier<DiscoveryServiceManagerMetrics> metricsFactory
    ) {
        var discoveryServiceManagerFactory = new DiscoveryServiceManagerFactory<>(new IncrementalDiscoveryServiceFactory<>(configBuilders), stateBacklogFactory);
        return new DeltaAggregatedDiscoveryService<>(clusterManager, discoveryServiceManagerFactory, clientConfigSource, listeners, metricsFactory);
    }

}
