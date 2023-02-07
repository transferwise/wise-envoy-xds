package com.transferwise.envoy.xds;

import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.transferwise.envoy.xds.api.IncrementalConfigBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Predicate;

@Slf4j
public abstract class AbstractDiscoveryService<T extends Message, E extends Message, StateUpdT, DetailsT> implements DiscoveryService<T, StateUpdT> {

    private final TypeUrl myTypeUrl;

    private final IncrementalConfigBuilder<E, StateUpdT, DetailsT> configBuilder;

    private final NodeConfig<DetailsT> nodeConfig;

    private StateUpdT currentState = null;

    private SubState currentSubState = SubState.COMPLETED;

    private boolean active = false;

    public AbstractDiscoveryService(TypeUrl myTypeUrl, IncrementalConfigBuilder<E, StateUpdT, DetailsT> configBuilder, NodeConfig<DetailsT> nodeConfig) {
        this.myTypeUrl = myTypeUrl;
        this.configBuilder = configBuilder;
        this.nodeConfig = nodeConfig;
    }

    protected abstract void processRequest(T value);

    @Override
    public void processUpdate(CommonDiscoveryRequest<T> value) {
        Preconditions.checkState(currentState != null, "Discovery must be initialized");
        if (value.getTypeUrl() == null) {
            throw new RuntimeException("Missing type URL on request");
        }
        TypeUrl typeUrl = TypeUrl.of(value.getTypeUrl());
        if (!myTypeUrl.equals(typeUrl)) {
            throw new RuntimeException(myTypeUrl.name() + " discovery service got called for request of type " + typeUrl.name());
        }
        active = true;
        processRequest(value.getMessage());
    }

    protected SubState getCurrentSubState() {
        return currentSubState;
    }

    protected void checkSubNames(IncrementalConfigBuilder.Resources<E> resources, Predicate<String> resourceInSubListChange) {
        if (resources.getResources().stream()
                .anyMatch(m -> !resourceInSubListChange.test(m.getName()))) {
            log.warn("Config builder for {} returned unasked for resources!", myTypeUrl.getTypeUrl());
        }
    }

    protected void checkSubNames(IncrementalConfigBuilder.Response<E> response, Predicate<String> resourceInSubListChange) {
        if (
                response.getRemoves().stream()
                        .anyMatch(m -> !resourceInSubListChange.test(m))
                || response.getAddAndUpdates().stream()
                        .anyMatch(m -> !resourceInSubListChange.test(m.getName()))
        ) {
            log.warn("Config builder for {} returned unasked for messages!", myTypeUrl.getTypeUrl());
        }
    }

    protected IncrementalConfigBuilder.Resources<E> getResources(Predicate<String> resourceInSubListChange) {
        final IncrementalConfigBuilder.Resources<E> resources;
        if (SubState.PRE.equals(currentSubState)) {
            resources = configBuilder.getResourcesAddOrder(currentState, resourceInSubListChange, nodeConfig.getXdsConfig().getClientDetails());
        } else {
            resources = configBuilder.getResourcesRemoveOrder(currentState, resourceInSubListChange, nodeConfig.getXdsConfig().getClientDetails());
        }
        checkSubNames(resources, resourceInSubListChange);
        return resources;
    }

    @Override
    public abstract boolean awaitingAck();

    @Override
    public void init(StateUpdT state) {
        Preconditions.checkState(currentState == null, "Discovery service already initialized");
        currentState = state;
    }

    @Override
    public void onNetworkUpdate(StateUpdT changes) {
        Preconditions.checkState(currentState != null, "Discovery must be initialized");
        if (changes.equals(currentState)) {
            return;
        }
        if (!SubState.COMPLETED.equals(currentSubState)) {
            throw new IllegalStateException("Network map update committed while still in dirty state!");
        }
        currentState = changes;
        currentSubState = SubState.PRE;
    }

    protected abstract Predicate<String> subFilter();

    protected boolean updateState(IncrementalConfigBuilder.Response<E> response) {
        return true;
    }

    protected Optional<IncrementalConfigBuilder.Response<E>> applyPre() {
        if (!SubState.PRE.equals(currentSubState)) {
            return Optional.empty();
        }
        currentSubState = SubState.POST;
        if (!active) {
            // We've never been asked for this resource. Since subFilter should return false for everything it ought to be safe to call configBuilder, but don't because:
            // a) It might do costly work we know we don't need;
            // b) It might return stuff. Envoy gets really angry if ADS returns a message type that it never requested.
            return Optional.empty();
        }
        IncrementalConfigBuilder.Response<E> resources = configBuilder.addOrder(currentState, subFilter(), nodeConfig.getXdsConfig().getClientDetails());
        checkSubNames(resources, subFilter());
        if (updateState(resources)) {
            return Optional.of(resources);
        }
        return Optional.empty();
    }

    protected abstract void pushNewState(IncrementalConfigBuilder.Response<E> response);

    @Override
    public void sendNetworkUpdatePre() {
        Preconditions.checkState(currentState != null, "Discovery must be initialized");
        applyPre().ifPresent(this::pushNewState);
    }

    protected Optional<IncrementalConfigBuilder.Response<E>> applyPost() {
        if (!SubState.POST.equals(currentSubState)) {
            return Optional.empty();
        }
        currentSubState = SubState.COMPLETED;
        if (!active) {
            // We've never been asked for this resource. Since subFilter should return false for everything it ought to be safe to call configBuilder, but don't because:
            // a) It might do costly work we know we don't need;
            // b) It might return stuff. Envoy gets really angry if ADS returns a message type that it never requested.
            return Optional.empty();
        }
        IncrementalConfigBuilder.Response<E> resources = configBuilder.removeOrder(currentState, subFilter(), nodeConfig.getXdsConfig().getClientDetails());
        checkSubNames(resources, subFilter());
        if (updateState(resources)) {
            return Optional.of(resources);
        }
        return Optional.empty();
    }

    @Override
    public void sendNetworkUpdatePost() {
        Preconditions.checkState(currentState != null, "Discovery must be initialized");
        applyPost().ifPresent(this::pushNewState);
    }

    protected Any pack(Message o) {
        return Any.pack(o);
    }

    @Override
    public TypeUrl getTypeUrl() {
        return myTypeUrl;
    }
}
