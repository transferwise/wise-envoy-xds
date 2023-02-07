package com.transferwise.envoy.xds.delta;

import com.google.common.collect.ImmutableSet;
import com.transferwise.envoy.xds.NodeConfig;
import java.util.Optional;
import java.util.function.Predicate;

class WildcardSubManager implements SubManager {

    private boolean firstResourceListChange = true;
    private boolean inWildcardMode = false;

    private final SubListSubManager subListSubManager;

    private final NodeConfig<?> nodeConfig;

    private static final String WILDCARD = "*";

    public WildcardSubManager(NodeConfig<?> nodeConfig) {
        this.nodeConfig = nodeConfig;
        this.subListSubManager = new SubListSubManager(nodeConfig);
    }

    @Override
    public Optional<Predicate<String>> processResourceListChange(ImmutableSet<String> resourceNamesSubscribe, ImmutableSet<String> resourceNamesUnsubscribe) {

        // The spec says to support old versions we should go into wildcard mode if this is the first request and the resource names is empty.
        // However, some older versions of envoy also have a bug where they will send a list of resource names after reconnecting to an ADS, even though they really want a wildcard subscription.
        try {
            if (firstResourceListChange && resourceNamesUnsubscribe.isEmpty()
                && (resourceNamesSubscribe.isEmpty() || nodeConfig.isBugClientSendsResourceListOnReconnectToWildcard())) {
                inWildcardMode = true;
                return Optional.of(s -> !s.equals(WILDCARD));
            }
        } finally {
            firstResourceListChange = false;
        }

        boolean subChanged = false;

        if (resourceNamesSubscribe.contains(WILDCARD)) {
            if (!inWildcardMode) {
                subChanged = true;
                inWildcardMode = true;
            }
            resourceNamesSubscribe = withoutWildcard(resourceNamesSubscribe);
        }
        if (resourceNamesUnsubscribe.contains(WILDCARD)) {
            inWildcardMode = false;
            resourceNamesUnsubscribe = withoutWildcard(resourceNamesUnsubscribe);
        }

        // The spec allows all discovery services to be operated in sublist mode, even those that support wildcards, so we delegate to a SubListSubManager instance to manage that.
        // The sublist should be maintained even in wildcard mode, as a client can unsubscribe from "*" and then expect the explicit subscriptions it sent previously to take effect.
        // We don't want to send "*" through though, as that's not a real resource name, so we strip it out before making the call.
        Optional<Predicate<String>> subListChange = subListSubManager.processResourceListChange(resourceNamesSubscribe, resourceNamesUnsubscribe);

        if (!inWildcardMode) {
            // If we're not in wildcard mode then the SubListSubManager is in charge.
            return subListChange;
        } else if (subChanged) {
            // If it wasn't subscribed to wildcard, but now it is then, then it has newly subscribed to all the things that it was not previously subscribed to.
            return Optional.of(s -> !s.equals(WILDCARD)
                && (
                    !subListSubManager.isSubscribedTo(s) // It was not subscribed
                    || subListChange.map(c -> c.test(s)).orElse(false) // This request subscribed to it explicitly as well as enabling the wildcard mode.
                )
            );
        } else {
            // Nothing happened
            return Optional.empty();
        }
    }

    private ImmutableSet<String> withoutWildcard(ImmutableSet<String> resourceNames) {
        return resourceNames.stream().filter(e -> !e.equals(WILDCARD)).collect(ImmutableSet.toImmutableSet());
    }

    @Override
    public boolean isSubscribedTo(String resourceName) {
        if (inWildcardMode) {
            return !resourceName.equals(WILDCARD);
        } else {
            return subListSubManager.isSubscribedTo(resourceName);
        }
    }
}
