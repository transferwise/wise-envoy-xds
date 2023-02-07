package com.transferwise.envoy.xds.api;

import com.transferwise.envoy.xds.XdsConfig;
import io.envoyproxy.envoy.config.core.v3.Node;

/**
 * Listener for events from the Xds.
 * This might be useful for tracking what clients are connected to your XDS.
 * @param <DetailsT> details type
 */
public interface XdsEventListener<DetailsT> {

    /**
     * Called when a client sends it's first Request message.
     * The clientHandle will be unique (object identity and .equals()) for each currently connected envoy instance.
     * A clientHandle may be reused again in the future, but a call to onClientDisconnected will be made before that can happen.
     * @param clientHandle ClientHandle uniquely identifying this connected client.
     * @param node The Node provided in the first Request message from envoy.
     * @param config The XdsConfig returned from your ClientConfigProvider for this envoy.
     */
    void onNewClient(ClientHandle clientHandle, Node node, XdsConfig<DetailsT> config);

    /**
     * Called when a client disconnects for some reason.
     * This will not normally be called unless onNewClient() has been called for a given connection, however implementations should accept that this might happen.
     * @param clientHandle The same instance of Object that was passed to onNewClient()
     * @param node The Node provided in the first Request message from envoy.
     */
    void onClientDisconnected(ClientHandle clientHandle, Node node);

}
