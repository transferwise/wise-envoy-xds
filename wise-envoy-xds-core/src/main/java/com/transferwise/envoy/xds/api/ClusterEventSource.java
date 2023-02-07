package com.transferwise.envoy.xds.api;

public interface ClusterEventSource<StateUpdT> {

    /**
     * Subscribe an event listener to this event source.
     * The event source is expected to hold a strong reference to the listener.
     * The event source must ensure that the initial state returned by this method is correctly sequenced with calls to the listener's onNetworkUpdate(),
     * i.e. you are responsible for ensuring that no network state changes are lost.
     * @param listener The listener who should receive future updates.
     * @return The last state update.
     */
    StateUpdT subscribe(ClusterManagerEventListener<StateUpdT> listener);

    /**
     * Stop listening to this event source.
     * Implementations must call close() on the listener if the listener was subscribed to the event source, and must release any references to the listener.
     * This may be called with a listener that was never subscribed to the event source.
     * @param listener The instance that should stop receiving updates.
     */
    void unsubscribe(ClusterManagerEventListener<StateUpdT> listener);

}
