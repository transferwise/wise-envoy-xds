package com.transferwise.envoy.xds;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.type.v3.SemanticVersion;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;

@Value
@Builder
public class NodeConfig<DetailsT> {

    XdsConfig<DetailsT> xdsConfig;

    /**
     * If true then treat a list of resource subscriptions as a wildcard on the first request for a discovery service that supports wildcards, even though the spec says only an empty list should be handled that way.
     * This works around a bug in envoy versions prior to 1.19.0 which sent a list of resources after reconnecting to an xDS, even when it wanted a wildcard subscription. This is safe since the xDS spec for the affected versions explicitly stated
     * that envoy would always use wildcards for the discovery services that supported it.
     */
    @Default
    boolean bugClientSendsResourceListOnReconnectToWildcard = false;

    public static <DetailsT> NodeConfig<DetailsT> forNode(Node node, XdsConfig<DetailsT> xdsConfig) {
        NodeConfigBuilder<DetailsT> builder = NodeConfig.<DetailsT>builder()
            .xdsConfig(xdsConfig);

        if (node.getUserAgentName().equals("envoy")) {
            SemanticVersion clientVersion = node.getUserAgentBuildVersion().getVersion();
            if (clientVersion.getMajorNumber() <= 1 && clientVersion.getMinorNumber() < 19) {
                builder.bugClientSendsResourceListOnReconnectToWildcard(true);
            }
        }

        return builder.build();
    }

}
