package com.transferwise.envoy.xds.sotw;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Managers for handling XDS resource hints:
 * https://github.com/envoyproxy/data-plane-api/blob/master/XDS_PROTOCOL.md#resource-hints
 * These are not required to be thread safe.
 */
interface SubManager {

    /**
     * Process a request to subscribe/unsubscribe from resources.
     * https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol#how-the-client-specifies-what-resources-to-return
     * Note: this returns a filter that matches only newly added resources. To filter for all currently subscribed resources use isSubscribedTo()
     *
     * @param resourceNames Set of all names envoy wants to be subscribed to.
     * @return Optional function that returns true for any resource name that was newly subscribed to, or not present if no subscription changes (neither add nor remove) were made.
     */
    Optional<Predicate<String>> processResourceListChange(List<String> resourceNames);

    <X> Map<String, X> filterSubs(Map<String, X> things);

    boolean isSubscribedTo(String resourceName);

}
