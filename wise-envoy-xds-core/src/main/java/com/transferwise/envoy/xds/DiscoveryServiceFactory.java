package com.transferwise.envoy.xds;

import com.google.protobuf.Message;
import io.grpc.stub.StreamObserver;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public interface DiscoveryServiceFactory<RequestT extends Message, ResponseT extends Message, StateUpdT, DetailsT> {

    DiscoveryService<RequestT, StateUpdT> createDiscoveryService(StreamObserver<ResponseT> responseObserver, NodeConfig<DetailsT> nodeConfig, TypeUrl type);

    default Map<TypeUrl, DiscoveryService<RequestT, StateUpdT>> createAll(StreamObserver<ResponseT> responseObserver, NodeConfig<DetailsT> nodeConfig) {
        return Arrays.stream(TypeUrl.values()).collect(Collectors.toMap(
                Function.identity(),
                t -> createDiscoveryService(responseObserver, nodeConfig, t)
            ));
    }

}
