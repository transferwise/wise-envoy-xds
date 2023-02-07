package com.transferwise.envoy.xds.delta;

import com.google.common.collect.ImmutableSet;
import com.transferwise.envoy.xds.NodeConfig;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

class SubListSubManager implements SubManager {

    public SubListSubManager(NodeConfig<?> unused) {
    }

    private final Set<String> subs = new HashSet<>();

    @Override
    public Optional<Predicate<String>> processResourceListChange(ImmutableSet<String> resourceNamesSubscribe, ImmutableSet<String> resourceNamesUnsubscribe) {
        Set<String> newSubs = new HashSet<>(resourceNamesSubscribe);

        for (String name: resourceNamesSubscribe) {
            subs.add(name);
            if (resourceNamesUnsubscribe.contains(name)) {
                // This should never happen, but if it does the xDS spec provides no guidance on correct behaviour.
                // Both possible options are potentially bad, so it seems best if this is an error.
                throw new IllegalArgumentException("Attempt to subscribe and unsubscribe from the same resource in one request: " + name);
            }
        }
        boolean didUnsubscribe = subs.removeAll(resourceNamesUnsubscribe);
        if (!didUnsubscribe && newSubs.isEmpty()) { // TODO(jono): why do we check didUnsubscribe here?
            return Optional.empty();
        }
        return Optional.of(newSubs::contains);
    }

    @Override
    public boolean isSubscribedTo(String resourceName) {
        return subs.contains(resourceName);
    }
}
