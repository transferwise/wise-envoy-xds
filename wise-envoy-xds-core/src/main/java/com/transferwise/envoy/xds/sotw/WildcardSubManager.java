package com.transferwise.envoy.xds.sotw;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

@Slf4j
class WildcardSubManager implements SubManager {

    private boolean subscribed = false;

    @VisibleForTesting
    boolean isSubscribed() {
        return subscribed;
    }

    @Override
    public Optional<Predicate<String>> processResourceListChange(List<String> resourceNames) {

        if (resourceNames.size() != 0) {
            throw new RuntimeException("Client had non empty resource names list");
        }
        if (!subscribed) {
            log.debug("Subscribed to *");
            subscribed = true;
            return Optional.of(name -> true);
        }
        return Optional.empty();
    }

    @Override
    public <X> Map<String, X> filterSubs(Map<String, X> things) {
        if (!subscribed) {
            return Collections.emptyMap();
        }
        return things;
    }

    @Override
    public boolean isSubscribedTo(String resourceName) {
        return subscribed;
    }


}
