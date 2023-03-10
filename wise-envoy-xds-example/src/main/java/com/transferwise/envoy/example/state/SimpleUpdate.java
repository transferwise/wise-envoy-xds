package com.transferwise.envoy.example.state;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.transferwise.envoy.xds.api.Mergeable;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;

/**
 * Generated by cluster manager when the network changes, these are used by configbuilders to decide what needs doing. This example simply sends the network before and after the change. Our real control plane has a more complex structure of
 * add/update/remove instead, which reduces the work each ConfigBuilder instance has to do (remember ConfigBuilders will have to be invoked for multiple envoys, while the update message will only need to be created once!)
 */
@Value
@Builder
public class SimpleUpdate implements Mergeable<SimpleUpdate> {

    @Default
    ImmutableMap<String, Service> before = ImmutableMap.of();

    @Default
    ImmutableMap<String, Service> after = ImmutableMap.of();

    public ImmutableCollection<Service> getServicesPreRemove() {
        return ImmutableMap.<String, Service>builder()
            .putAll(before)
            .putAll(after)
            .buildKeepingLast()
            .values();
    }

    public ImmutableCollection<Service> getServicesPostRemove() {
        return after.values();
    }

    @Override
    public SimpleUpdate merge(SimpleUpdate b) {
        // Since our updates are simply the state before and after the change, we can merge them by keeping the oldest before and the latest after.
        return SimpleUpdate.builder()
            .before(this.getBefore())
            .after(b.getAfter())
            .build();
    }
}
