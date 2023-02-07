package com.transferwise.envoy.xds.delta;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Message;
import com.transferwise.envoy.xds.AbstractDiscoveryService;
import com.transferwise.envoy.xds.ClientNackException;
import com.transferwise.envoy.xds.NodeConfig;
import com.transferwise.envoy.xds.TypeUrl;
import com.transferwise.envoy.xds.api.IncrementalConfigBuilder;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.grpc.stub.StreamObserver;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class IncrementalDiscoveryService<E extends Message, StateUpdT, DetailsT> extends AbstractDiscoveryService<DeltaDiscoveryRequest, E, StateUpdT, DetailsT> {

    private final StreamObserver<DeltaDiscoveryResponse> responseObserver;

    private String lastNonce = null;

    private Long version = 0L;

    private final SubManager subManager;

    IncrementalDiscoveryService(TypeUrl myTypeUrl, StreamObserver<DeltaDiscoveryResponse> responseObserver, IncrementalConfigBuilder<E, StateUpdT, DetailsT> configBuilder, NodeConfig<DetailsT> nodeConfig,
                                SubManager subManager) {
        super(myTypeUrl, configBuilder, nodeConfig);
        this.responseObserver = responseObserver;
        this.subManager = subManager;
    }

    @Override
    protected void processRequest(DeltaDiscoveryRequest value) {
        boolean isAck = !Strings.isNullOrEmpty(value.getResponseNonce());
        if (isAck) {
            // Envoy is trying to ACK something.
            if (value.hasErrorDetail()) {
                throw new ClientNackException(value.getResponseNonce(), value.getErrorDetail());
            }
            if (value.getResponseNonce().equals(lastNonce)) {
                log.debug("{} ACKed {}", getTypeUrl().name(), lastNonce);
                lastNonce = null; // acked!
            }
        }
        ImmutableSet<String> subscribeSet = ImmutableSet.copyOf(value.getResourceNamesSubscribeList());
        ImmutableSet<String> unsubscribeSet = ImmutableSet.copyOf(value.getResourceNamesUnsubscribeList());
        subManager.processResourceListChange(subscribeSet, unsubscribeSet).ifPresent(newSubFilter -> {
            // Subscriptions changed.
            if (isAck) {
                // The xDS docs suggest that envoy won't both ack something and change subscriptions lists in the same request.
                // But I don't entirely trust that it won't. So we support that case, but log about it.
                log.info("Client included subscriptions changes in an ack!");
            }
            processSubUpdate(subscribeSet, newSubFilter, ImmutableSet.copyOf(value.getInitialResourceVersionsMap().keySet()));
        });
    }

    @Override
    public Predicate<String> subFilter() {
        return subManager::isSubscribedTo;
    }

    private void processSubUpdate(ImmutableSet<String> newSubs, Predicate<String> filter, ImmutableSet<String> initialState) {
        log.debug("{} subscription change - added: {}", getTypeUrl().name(), newSubs);

        // Envoy will want us to tell it about everything it's just asked for, assuming it successfully subscribed to it.
        // If the resource doesn't exist, however, we will need to tell it to delete it.
        // We check against the filter because some values in resource name lists have special meaning to sub management (e.g. wildcards),
        // they're not actually resource names.
        HashSet<String> removed = newSubs.stream().filter(filter).collect(Collectors.toCollection(HashSet::new));
        // If envoy sent us an initial resource versions map it might also contain things that don't exist anymore.
        // Again, we will need to tell envoy to delete it, but only if envoy has subscribed for updates about it.
        // This is necessary because envoy doesn't explicitly subscribe to named resources (e.g. when it uses wildcards), so it can be
        // subscribed to things it has never explicitly asked us for.
        initialState.stream().filter(filter).forEach(removed::add);

        // Now actually generate the resources for this sub update.
        IncrementalConfigBuilder.Resources<E> resources = getResources(filter);

        for (IncrementalConfigBuilder.NamedMessage<E> msg: resources.getResources()) {
            // If we generated a resource we don't want to tell envoy to remove it.
            removed.remove(msg.getName());
        }

        pushResources(resources.getResources(), removed);
    }

    @Override
    public boolean awaitingAck() {
        return lastNonce != null;
    }

    @Override
    public void pushNewState(IncrementalConfigBuilder.Response<E> response) {
        if (response.getAddAndUpdates().isEmpty() && response.getRemoves().isEmpty()) {
            return;
        }
        pushResources(response.getAddAndUpdates(), response.getRemoves());
    }

    private void pushResources(Collection<IncrementalConfigBuilder.NamedMessage<E>> resources, Collection<String> removals) {
        Long myVersion = ++version;
        DeltaDiscoveryResponse.Builder responseBuilder = DeltaDiscoveryResponse.newBuilder();
        for (IncrementalConfigBuilder.NamedMessage<? extends Message> namedMessage : resources) {
            responseBuilder.addResources(Resource.newBuilder()
                    .setName(namedMessage.getName())
                    .setVersion(myVersion.toString())
                    .setResource(pack(namedMessage.getMessage()))
                    .build());
        }
        responseBuilder.addAllRemovedResources(removals);
        responseBuilder.setTypeUrl(getTypeUrl().getTypeUrl());
        lastNonce = UUID.randomUUID().toString();
        responseBuilder.setNonce(lastNonce);
        responseBuilder.setSystemVersionInfo(myVersion.toString());

        DeltaDiscoveryResponse discoveryResponse = responseBuilder.build();

        if (log.isDebugEnabled()) {
            log.debug("{} Pushing update {} change - added: {} removed: {}", getTypeUrl().name(), lastNonce, resources.stream().map(IncrementalConfigBuilder.NamedMessage::getName).collect(Collectors.toList()), removals);
        }

        responseObserver.onNext(discoveryResponse);
    }

}
