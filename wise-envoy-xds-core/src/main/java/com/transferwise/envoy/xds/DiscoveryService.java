package com.transferwise.envoy.xds;

import com.google.protobuf.Message;

/**
 * Handles communication with a specific envoy client for a specific Discovery Service type.
 *
 * @param <RequestT> the DiscoveryRequest or DeltaDiscoveryRequest message type.
 */
public interface DiscoveryService<RequestT extends Message, StateUpdT> {

    enum SubState {
        COMPLETED,
        PRE,
        POST
    }

    /**
     * Called when envoy either acknowledges an outstanding update, or sends a new xDS request.
     * init must have be called before this method is called for the first time.
     * @param value the request sent by envoy.
     */
    void processUpdate(CommonDiscoveryRequest<RequestT> value);

    /**
     * Tells if it is envoy's turn in sending an ack for an outstanding update.
     *
     * @return true if an ack is outstanding.
     */
    boolean awaitingAck();

    /**
     * <p>Called when the DiscoveryServiceManager is initialized.</p>
     * <p>The DSM is initialized the first time any message from envoy is received, regardless of which DiscoveryService will handle the message.</p>
     * <p>The state passed can either be a genuinely full state (with only added services),
     * or a differential state (from which the full state can still be reconstructed).</p>
     */
    void init(StateUpdT state);

    /**
     * Stores the changes to be sent during the following sendNetworkUpdatePre/Post calls.
     * init must have be called before this method is called for the first time.
     */
    void onNetworkUpdate(StateUpdT changes);

    /**
     * Sends parts of the previously stored changes applicable during the preorder round.
     * These are typically the additions.
     */
    void sendNetworkUpdatePre();

    /**
     * Sends parts of the previously stored changes applicable during the postorder round.
     * These are typically removals.
     */
    void sendNetworkUpdatePost();

    /**
     * Identifies the specific Discover Service type this instance handles.
     */
    TypeUrl getTypeUrl();
}
