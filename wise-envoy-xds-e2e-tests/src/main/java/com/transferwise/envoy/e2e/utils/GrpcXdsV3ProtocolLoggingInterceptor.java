package com.transferwise.envoy.e2e.utils;

import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.grpc.Status.Code;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GrpcXdsV3ProtocolLoggingInterceptor extends SimpleGRpcInterceptor {

    private final Map<Class<?>, MessageLogger> messageLoggers;

    public GrpcXdsV3ProtocolLoggingInterceptor() {
        messageLoggers = Map.of(
            DiscoveryRequest.class, this::discoveryRequest,
            DiscoveryResponse.class, this::discoveryResponse,
            DeltaDiscoveryRequest.class, this::deltaDiscoveryRequest,
            DeltaDiscoveryResponse.class, this::deltaDiscoveryResponse
        );
    }

    void discoveryRequest(Object message) {
        var concrete = (DiscoveryRequest) message;
        var prefix = "?";
        var typeUrl = concrete.getTypeUrl();
        var type = typeUrl.substring(typeUrl.lastIndexOf(".") + 1);
        var nonce = concrete.getResponseNonce();
        var shortNonce = nonce.length() > 5 ? nonce.substring(0, 5) : "";
        var version = concrete.getVersionInfo();
        var resources = concrete.getResourceNamesList();

        log.debug(String.format(
            "%-5s  T:%-25s  N:%-5s  V:%-3s  R:%s",
            prefix,
            type,
            shortNonce,
            version,
            resources
        ));
    }

    void deltaDiscoveryRequest(Object message) {
        var concrete = (DeltaDiscoveryRequest) message;
        var prefix = "?++";
        var typeUrl = concrete.getTypeUrl();
        var type = typeUrl.substring(typeUrl.lastIndexOf(".") + 1);
        var nonce = concrete.getResponseNonce();
        var shortNonce = nonce.length() > 5 ? nonce.substring(0, 5) : "";
        var version = concrete.getInitialResourceVersionsMap();
        var resourcesSubscribe = concrete.getResourceNamesSubscribeList();
        var resourcesUnsubscribe = concrete.getResourceNamesUnsubscribeList();
        var error = concrete.getErrorDetail().getCode() != Code.OK.value() ? "ERR:" + concrete.getErrorDetail().getMessage() : "";

        log.debug(String.format(
            "%-5s  T:%-25s  N:%-5s  V:%s  S+:%s  S-:%s  %s",
            prefix,
            type,
            shortNonce,
            version,
            resourcesSubscribe,
            resourcesUnsubscribe,
            error
        ));
    }

    void discoveryResponse(Object message) {
        var concrete = (DiscoveryResponse) message;
        var prefix = "=";
        var typeUrl = concrete.getTypeUrl();
        var type = typeUrl.substring(typeUrl.lastIndexOf(".") + 1);
        var nonce = concrete.getNonce().substring(0, 5);
        var version = concrete.getVersionInfo();
        var resources = concrete.getResourcesList().stream().map(any -> {
            try {
                if (any.is(ClusterLoadAssignment.class)) {
                    return any.unpack(ClusterLoadAssignment.class).getClusterName();
                } else if (any.is(Cluster.class)) {
                    return any.unpack(Cluster.class).getName();
                } else if (any.is(Listener.class)) {
                    return any.unpack(Listener.class).getName();
                } else if (any.is(RouteConfiguration.class)) {
                    return any.unpack(RouteConfiguration.class).getName();
                }
            } catch (InvalidProtocolBufferException e) {
                log.error("Unable to unpack resource", e);
            }

            return "?";
        }).toList();

        log.debug(String.format(
            "%-5s  T:%-25s  N:%-5s  V:%-3s  R:%s",
            prefix,
            type,
            nonce,
            version,
            resources
        ));
    }

    void deltaDiscoveryResponse(Object message) {
        var concrete = (DeltaDiscoveryResponse) message;
        var prefix = "=++";
        var typeUrl = concrete.getTypeUrl();
        var type = typeUrl.substring(typeUrl.lastIndexOf(".") + 1);
        var nonce = concrete.getNonce().substring(0, 5);
        var resourcesAdded = concrete.getResourcesList().stream()
            .map(Resource::getName).toList();
        var resourcesRemoved = concrete.getRemovedResourcesList();

        log.debug(String.format(
            "%-5s  T:%-25s  N:%5s  R+:%s  R-:%s ",
            prefix,
            type,
            nonce,
            resourcesAdded,
            resourcesRemoved
        ));
    }

    @Override
    protected void onMessage(CallRole callRole, MessageDirection dir, Object message) {
        if (log.isDebugEnabled()) {
            var messageLogger = messageLoggers.getOrDefault(message.getClass(), null);
            if (messageLogger != null) {
                messageLogger.log(message);
            }
        }
    }

    interface MessageLogger {

        void log(Object message);
    }
}
