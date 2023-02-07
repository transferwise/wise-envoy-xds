package com.transferwise.envoy.xds.delta;

import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Managers for handling Delta XDS subscriptions.
 * These are not required to be thread safe.
 */
interface SubManager {

    /**
     * Process a request to subscribe/unsubscribe from resources.
     * <a href="https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol#how-the-client-specifies-what-resources-to-return">XDS Protocol spec: How the Client Specifies What Resources to Return</a>
     * Note: this returns a filter that matches only newly added resources. To filter for all currently subscribed resources use isSubscribedTo()
     *
     * @param resourceNamesSubscribe Set of names to be added to the current subscriptions.
     * @param resourceNamesUnsubscribe Set of names being removed from the current subscriptions.
     * @return Optional function that returns true for any resource name that was newly subscribed to, or not present if no subscription changes (neither add nor remove) were made.
     */
    Optional<Predicate<String>> processResourceListChange(ImmutableSet<String> resourceNamesSubscribe, ImmutableSet<String> resourceNamesUnsubscribe);

    /**
     * Find out if we're subscribed to a given resource name.
     */
    boolean isSubscribedTo(String resourceName);

}
