package com.transferwise.envoy.xds;

import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

import io.envoyproxy.envoy.config.route.v3.ScopedRouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.service.runtime.v3.Runtime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public enum TypeUrl {
    LDS(Listener.getDescriptor().getFullName(), Listener.class, true),
    RDS(RouteConfiguration.getDescriptor().getFullName(), RouteConfiguration.class, false),
    SRDS(ScopedRouteConfiguration.getDescriptor().getFullName(), ScopedRouteConfiguration.class, false),
    VHDS(VirtualHost.getDescriptor().getFullName(), VirtualHost.class, false),
    CDS(Cluster.getDescriptor().getFullName(), Cluster.class, true),
    EDS(ClusterLoadAssignment.getDescriptor().getFullName(), ClusterLoadAssignment.class, false),
    SDS(Secret.getDescriptor().getFullName(), Secret.class, false),
    RTDS(Runtime.getDescriptor().getFullName(), Runtime.class, false);

    private final String typeUrl;

    private final Class<? extends Message> messageClazz;

    private final boolean wildcard;

    private static final Set<Class<? extends Message>> messageClazzes = Arrays.stream(TypeUrl.values()).map(TypeUrl::getMessageClazz).collect(Collectors.toSet());

    TypeUrl(String className, Class<? extends Message> messageClazz, boolean wildcard) {
        this.typeUrl = "type.googleapis.com/" + className;
        this.messageClazz = messageClazz;
        this.wildcard = wildcard;
    }

    public String getTypeUrl() {
        return typeUrl;
    }

    public static TypeUrl of(String typeUrl) {
        return Arrays.stream(TypeUrl.values()).filter(
                t -> t.getTypeUrl().equals(typeUrl)
        ).findAny().orElse(null);
    }

    /**
     * Order in which to apply discovery services for addition of resources.
     * This order is partially documented in the xDS spec, but some of it is guesswork (and for some ordering probably does not matter.)
     */
    public static final List<TypeUrl> ADD_ORDER = List.of(SDS, RTDS, CDS, EDS, LDS, RDS, SRDS, VHDS);
    /**
     * Order in which to apply discovery services for removal of resources.
     * This order is partially documented in the xDS spec, but some of it is guesswork (and for some ordering probably does not matter.)
     */
    public static final List<TypeUrl> REMOVE_ORDER = List.of(LDS, RDS, SRDS, VHDS, CDS, EDS, RTDS, SDS);

    public Class<? extends Message> getMessageClazz() {
        return messageClazz;
    }

    public static Set<Class<? extends Message>> getMessageClazzes() {
        return messageClazzes;
    }

    public boolean isWildcard() {
        return wildcard;
    }
}
