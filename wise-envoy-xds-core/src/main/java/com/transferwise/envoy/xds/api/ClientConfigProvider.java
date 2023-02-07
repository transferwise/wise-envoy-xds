package com.transferwise.envoy.xds.api;

import com.transferwise.envoy.xds.XdsConfig;
import io.envoyproxy.envoy.config.core.v3.Node;

/**
 * Provide configuration and other information for a client envoy's session.
 * @param <DetailsT> Information you need to build the xDS resources for this client. This might also include server configuration, whatever you want. We don't care what this is.
 */
public interface ClientConfigProvider<DetailsT> {

    /**
     * Look up the XdsConfig and details for a client (envoy instance.).
     * This will only be called when an envoy sends it's first request, not on any subsequent requests.
     * Implementations may also safely access the GRPC Context for additional information about the client.
     * @param node the Node structure provided by envoy on its first Request.
     * @return Configuration to use with this client.
     */
    XdsConfig<DetailsT> lookup(Node node);

}
