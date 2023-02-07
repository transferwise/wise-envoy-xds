package com.transferwise.envoy.example;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.transferwise.envoy.xds.api.ClusterEventSource;
import com.transferwise.envoy.xds.api.ClusterManagerEventListener;
import com.transferwise.envoy.xds.api.utils.QueueingMergingEventListener;
import com.transferwise.envoy.example.state.Service;
import com.transferwise.envoy.example.state.SimpleUpdate;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.concurrent.GuardedBy;

/**
 * Our cluster manager keeps track of the current network state. It's responsible for informing subscribers about changes to the system state. In this example we expose a handful of public methods that are used to allow XdsExample to add/remove
 * endpoints from our network, and inspect the current state.
 */
public class ClusterManager implements ClusterEventSource<SimpleUpdate> {

    /**
     * Maps ClustermanagerEventListeners that actually subscribed to this source to the QueueingMergingEventListener that we've wrapped them in.
     */
    private final ConcurrentHashMap<ClusterManagerEventListener<SimpleUpdate>, QueueingMergingEventListener<SimpleUpdate>> targets = new ConcurrentHashMap<>();

    @GuardedBy("this")
    private ImmutableMap<String, Service> currentState = ImmutableMap.of();

    private void sendUpdate(SimpleUpdate update) {
        // A real implementation should probably catch errors and not explode if a listener fails!
        targets.values().forEach(t -> t.onNetworkChange(update));
    }

    @Override
    public synchronized SimpleUpdate subscribe(ClusterManagerEventListener<SimpleUpdate> listener) {
        // Subscribers may do heavy lifting in their onNetworkChange methods. To avoid this blocking the cluster manager we wrap them in a queue backed listener which will submit calls to an executor service.
        // This particular listener implementation takes advantage of the fact that SimpleUpdate implements Mergeable, so it will also compress the queue into a single call before executing it.
        targets.computeIfAbsent(listener, QueueingMergingEventListener::createAndStart);
        return SimpleUpdate.builder().after(currentState).build();
    }

    @Override
    public void unsubscribe(ClusterManagerEventListener<SimpleUpdate> listener) {
        ClusterManagerEventListener<SimpleUpdate> old = targets.remove(listener);
        if (old != null) {
            old.close();
        }
    }

    public synchronized void setEndpoints(String service, HostAndPort... endpoints) {
        ImmutableMap<String, Service> after = ImmutableMap.<String, Service>builder()
            .putAll(currentState)
            .put(service, Service.builder().name(service).endpoints(Arrays.asList(endpoints)).build())
            .buildKeepingLast();

        applyUpdate(after);
    }

    public synchronized void setEndpoints(ImmutableMap<String, ImmutableList<HostAndPort>> services) {
        ImmutableMap.Builder<String, Service> after = ImmutableMap.<String, Service>builder()
            .putAll(currentState);

        for (Entry<String, ImmutableList<HostAndPort>> entry : services.entrySet()) {
            after.put(entry.getKey(), Service.builder().name(entry.getKey()).endpoints(entry.getValue()).build());
        }

        applyUpdate(after.buildKeepingLast());
    }

    public synchronized void addEndpoint(String service, HostAndPort endpoint) {
        Service oldValue = currentState.get(service);
        final ImmutableSet<HostAndPort> addresses;
        if (oldValue != null) {
            addresses = ImmutableSet.<HostAndPort>builder().addAll(oldValue.getEndpoints()).add(endpoint).build();
        } else {
            addresses = ImmutableSet.of(endpoint);
        }
        setEndpoints(service, addresses.toArray(new HostAndPort[0]));
    }

    public synchronized void removeEndpoint(String service, HostAndPort endpoint) {
        Service oldValue = currentState.get(service);
        if (oldValue == null) {
            return;
        }
        ImmutableSet<HostAndPort> addresses = oldValue.getEndpoints().stream().filter(e -> !e.equals(endpoint)).collect(ImmutableSet.toImmutableSet());
        setEndpoints(service, addresses.toArray(new HostAndPort[0]));
    }

    public synchronized void removeService(String name) {
        if (!currentState.containsKey(name)) {
            return;
        }
        ImmutableMap<String, Service> after = currentState.entrySet().stream()
            .filter(e -> !e.getKey().equals(name))
            .collect(
                ImmutableMap.toImmutableMap(
                    Entry::getKey,
                    Entry::getValue
                )
            );
        applyUpdate(after);
    }

    public synchronized ImmutableMap<String, Service> getState() {
        return currentState;
    }

    @GuardedBy("this")
    private void applyUpdate(ImmutableMap<String, Service> newState) {
        SimpleUpdate update = SimpleUpdate.builder()
            .before(currentState)
            .after(newState)
            .build();

        currentState = newState;
        sendUpdate(update);
    }

}
