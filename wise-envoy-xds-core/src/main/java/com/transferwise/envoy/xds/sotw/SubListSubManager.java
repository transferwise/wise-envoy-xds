package com.transferwise.envoy.xds.sotw;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
class SubListSubManager implements SubManager {

    private Set<String> subs = new HashSet<>();

    @VisibleForTesting
    Set<String> getSubs() {
        return subs;
    }

    @Override
    public Optional<Predicate<String>> processResourceListChange(List<String> resourceNames) {

        boolean subsUpdated = false;

        Set<String> newSubs = new HashSet<>();
        for (String resourceName: resourceNames) {
            if (!subs.contains(resourceName)) {
                newSubs.add(resourceName);
                subsUpdated = true;
            }
        }
        if (subsUpdated || subs.size() != resourceNames.size()) {
            log.debug("Subscribed to {}", resourceNames);
            subs = new HashSet<>(resourceNames);
            return Optional.of(newSubs::contains);
        }
        return Optional.empty();
    }

    @Override
    public <X> Map<String, X> filterSubs(Map<String, X> things) {
        if (subs == null) {
            return Collections.emptyMap();
        }
        return things.entrySet().stream().filter((e) -> subs.contains(e.getKey())).collect(
                Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                )
        );
    }

    @Override
    public boolean isSubscribedTo(String resourceName) {
        return subs.contains(resourceName);
    }
}
