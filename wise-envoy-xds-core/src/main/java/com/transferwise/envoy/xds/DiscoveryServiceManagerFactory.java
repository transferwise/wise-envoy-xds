package com.transferwise.envoy.xds;

import com.google.protobuf.Message;
import com.transferwise.envoy.xds.api.StateBacklog;
import com.transferwise.envoy.xds.api.StateBacklogFactory;
import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;
import io.grpc.stub.StreamObserver;

import java.util.Map;

public class DiscoveryServiceManagerFactory<RequestT extends Message, ResponseT extends Message, StateUpdT, DetailsT> {

    private final DiscoveryServiceFactory<RequestT, ResponseT, StateUpdT, DetailsT> discoveryServiceFactory;
    private final StateBacklogFactory<StateUpdT> waitingStateBacklogFactory;

    public DiscoveryServiceManagerFactory(DiscoveryServiceFactory<RequestT, ResponseT, StateUpdT, DetailsT> discoveryServiceFactory, StateBacklogFactory<StateUpdT> waitingStateBacklogFactory) {
        this.discoveryServiceFactory = discoveryServiceFactory;
        this.waitingStateBacklogFactory = waitingStateBacklogFactory;
    }

    public DiscoveryServiceManager<RequestT, StateUpdT> build(StreamObserver<ResponseT> responseObserver, NodeConfig<DetailsT> nodeConfig, DiscoveryServiceManagerMetrics metrics) {
        return build(discoveryServiceFactory.createAll(responseObserver, nodeConfig), waitingStateBacklogFactory.build(), metrics);
    }

    public DiscoveryServiceManager<RequestT, StateUpdT> build(Map<TypeUrl, DiscoveryService<RequestT, StateUpdT>> discoveryServices, StateBacklog<StateUpdT> waitingStateBacklog, DiscoveryServiceManagerMetrics metrics) {
        return new DiscoveryServiceManager<>(discoveryServices, TypeUrl.ADD_ORDER, TypeUrl.REMOVE_ORDER, waitingStateBacklog, metrics);
    }

}
