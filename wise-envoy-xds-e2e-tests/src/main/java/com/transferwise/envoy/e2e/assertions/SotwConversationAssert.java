package com.transferwise.envoy.e2e.assertions;

import static com.transferwise.envoy.e2e.configdump.EnvoyAdminClient.sneakyUnpack;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.Any;
import com.transferwise.envoy.e2e.utils.ConversationLogger.Conversation;
import com.transferwise.envoy.xds.TypeUrl;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.AssertFactory;
import org.assertj.core.api.InstanceOfAssertFactory;

public class SotwConversationAssert extends EnvoyAssert<SotwConversationAssert> {


    public static InstanceOfAssertFactory<Conversation, SotwConversationAssert> SOTW_CONVERSATION = new InstanceOfAssertFactory<>(Conversation.class, new Factory());

    protected SotwConversationAssert(Conversation actual) {
        super(actual, SotwConversationAssert.class);
    }

    public static SotwConversationAssert assertThat(Conversation actual) {
        return new SotwConversationAssert(actual);
    }

    @Override
    @CanIgnoreReturnValue
    public SotwConversationAssert allWereAcked() {
        isNotNull();
        SetMultimap<String, String> sentNonces = HashMultimap.create();
        SetMultimap<String, String> unackedNonces = HashMultimap.create();

        for (Object message : actual.getMessages()) {
            if (message instanceof DiscoveryRequest r) {
                // TODO: We probably should check versions for SOTW...
                // TODO: Don't accept old nonces once envoy has acked newer ones...
                if (Strings.emptyToNull(r.getResponseNonce()) != null) {
                    if (!sentNonces.containsKey(r.getTypeUrl())) {
                        // If envoy is reconnecting it may reuse nonces from its previous connection.
                        sentNonces.put(r.getTypeUrl(), r.getResponseNonce());
                    } else {
                        if (!sentNonces.containsEntry(r.getTypeUrl(), r.getResponseNonce())) {
                            failWithMessage("Found unexpected response nonce <%s> for <%s>, expected one of <%s>", r.getResponseNonce(), r.getTypeUrl(), sentNonces.get(r.getTypeUrl()));
                        }
                    }
                    unackedNonces.remove(r.getTypeUrl(), r.getResponseNonce());
                }
                if (r.hasErrorDetail()) {
                    failWithMessage("Envoy sent error <%s>", r.getErrorDetail().getMessage());
                }
            } else if (message instanceof DiscoveryResponse r) {
                if (!sentNonces.put(r.getTypeUrl(), r.getNonce())) {
                    failWithMessage("Sent nonce <%s> for <%s> multiple times", r.getNonce(), r.getTypeUrl());
                }
                unackedNonces.put(r.getTypeUrl(), r.getNonce());
            } else {
                failWithMessage("Expected message to be of type DiscoveryRequest or DiscoveryResponse, but found <%s>", message.getClass());
            }
        }
        if (!unackedNonces.isEmpty()) {
            failWithMessage("Found unacked nonces <%s>", unackedNonces);
        }
        return this;
    }

    @Override
    @CanIgnoreReturnValue
    public SotwConversationAssert nothingSentUnaskedPre119Reconnect() {
        return nothingSentUnasked(true);
    }

    @Override
    @CanIgnoreReturnValue
    public SotwConversationAssert nothingSentUnasked() {
        return nothingSentUnasked(false);
    }

    @Override
    @CanIgnoreReturnValue
    public SotwConversationAssert nothingSentUnasked(boolean allowBrokenReconnectWildcardBehaviourPre119) {
        isNotNull();

        HashMultimap<String, String> subs = HashMultimap.create();
        HashSet<String> wildcards = new HashSet<>();
        HashSet<String> seen = new HashSet<>();
        HashSet<String> seenSubs = new HashSet<>();

        for (Object message : actual.getMessages()) {
            if (message instanceof DiscoveryRequest r) {
                Set<String> requestedSubs = Set.copyOf(r.getResourceNamesList());
                if (seen.add(r.getTypeUrl()) && (TypeUrl.of(r.getTypeUrl()).isWildcard() && allowBrokenReconnectWildcardBehaviourPre119)) {
                    // First request, allow bad behaviour from old versions.
                    requestedSubs = Set.of();
                }
                if (requestedSubs.isEmpty() && !seenSubs.contains(r.getTypeUrl())) {
                    // Old wildcard behaviour
                    wildcards.add(r.getTypeUrl());
                } else {
                    seenSubs.add(r.getTypeUrl()); // Track that this xDS has actually asked for things so that we expect old behaviour to be disabled.
                    if (requestedSubs.contains("*")) {
                        wildcards.add(r.getTypeUrl());
                        requestedSubs = requestedSubs.stream().filter(s -> !s.equals("*")).collect(Collectors.toSet());
                    } else {
                        wildcards.remove(r.getTypeUrl());
                    }
                    subs.replaceValues(r.getTypeUrl(), requestedSubs);
                }
            } else if (message instanceof DiscoveryResponse r) {
                if (!wildcards.contains(r.getTypeUrl())) {
                    if (!subs.containsKey(r.getTypeUrl())) {
                        failWithMessage("Sent message for type <%s> which envoy has never asked for", r.getTypeUrl());
                    }
                    List<String> unwanted = r.getResourcesList().stream()
                        .map(this::getNameForResource)
                        .filter(resName -> !subs.get(r.getTypeUrl()).contains(resName)).toList();
                    if (!unwanted.isEmpty()) {
                        failWithMessage("Sent message for type <%s> containing resource(s) <%s> which envoy has never asked for", r.getTypeUrl(), unwanted);
                    }
                }
            } else {
                failWithMessage("Expected message to be of type DeltaDiscoveryRequest or DeltaDiscoveryResponse, but found <%s>", message.getClass());
            }
        }
        return this;
    }

    private String getNameForResource(Any any) {
        Object resource = sneakyUnpack(any, TypeUrl.of(any.getTypeUrl()).getMessageClazz());
        if (resource instanceof Cluster c) {
            return c.getName();
        }
        if (resource instanceof ClusterLoadAssignment cla) {
            return cla.getClusterName();
        }
        if (resource instanceof Listener l) {
            return l.getName();
        }
        if (resource instanceof RouteConfiguration r) {
            return r.getName();
        }
        failWithMessage("Found message of type I don't know how to decode <%s>", any.getTypeUrl());
        return null;
    }

    @Override
    @CanIgnoreReturnValue
    public SotwConversationAssert hadConversationsWith(String... typeUrls) {
        isNotNull();

        Set<String> needed = new HashSet<>(List.of(typeUrls));
        for (Object message : actual.getMessages()) {
            if (message instanceof DiscoveryRequest r) {
                needed.remove(r.getTypeUrl());
                if (needed.isEmpty()) {
                    return this;
                }
            }
        }
        if (!needed.isEmpty()) {
            failWithMessage("Did not have a conversation with <%s>", needed);
        }
        return this;
    }

    public static class Factory implements AssertFactory<Conversation, SotwConversationAssert> {

        @Override
        public SotwConversationAssert createAssert(Conversation conversation) {
            return SotwConversationAssert.assertThat(conversation);
        }
    }

}
