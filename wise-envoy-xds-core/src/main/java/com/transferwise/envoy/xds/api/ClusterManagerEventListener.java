package com.transferwise.envoy.xds.api;

import java.io.Closeable;

public interface ClusterManagerEventListener<StateUpdT> extends Closeable {

    /**
     * Call when stuff in your network changes.
     * This must not be called before the listener has called subscribe on the ClusterEventSource that will be calling this method.
     * In some implementations calls to this method may still occur after close() has been called, so they should not be treated as an error.
     * @param diff State update.
     */
    void onNetworkChange(StateUpdT diff);

    /**
     * Should be called when the listener has been successfully unsubscribed from events.
     * Further calls to onNetworkChange() after close() will have no effect.
     * Implementations might use this to stop processing threads, or perform other cleanup tasks.
     */
    @Override
    default void close() {

    }
}
