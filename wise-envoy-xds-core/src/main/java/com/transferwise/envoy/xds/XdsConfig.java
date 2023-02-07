package com.transferwise.envoy.xds;

import lombok.Builder;
import lombok.Value;

/**
 * This represents the metadata and configuration of the controlplane for a specific client.
 */
@Value
@Builder
public class XdsConfig<DetailsT> {

    /**
     * Whatever details about the client and configuration that you need to pass to your ConfigBuilders.
     */
    DetailsT clientDetails;

    /**
     * If set then we will delay sending mesh updates to the client until we have received the first ACK for this TypeUrl.
     * This allows us to prevent clients being sent endpoint updates, which interfere with the envoy init process, until
     * they have acked a later DS (for example RDS or LDS, which are only ACKed by envoy after clusters have been fully
     * initialised.)
     * If null then we don't delay the updates.
     * This is useful, for example, to work around bugs like https://github.com/envoyproxy/envoy/issues/16035 (we set this option to RDS in our own control plane for this reason.)
     */
    TypeUrl delayUpdatesUntilAckOf;

    /**
     * Set to true to quietly ignore a nack from envoy.
     * The default behaviour would be to log an error and disconnect the client, since we have no good way to recover from inconsistent state. If you have a bad client then this gets really spammy, since the client will keep re-connecting in
     * a tight loop (by default) and trigger the same error over and over. Normally such errors will be due to producing invalid config in some corner cases, or some serious desync issue in our xds implementation, in which case re-connecting will
     * maybe clear the problem.
     * But if you've got some clients that are e.g. developer laptops, which could be running out of date envoy versions, then it's crazy noisy. Set this to true for them.
     */
    boolean silentNacks;
}
