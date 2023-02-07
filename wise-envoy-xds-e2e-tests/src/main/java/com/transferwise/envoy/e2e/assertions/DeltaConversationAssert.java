package com.transferwise.envoy.e2e.assertions;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.transferwise.envoy.e2e.utils.ConversationLogger.Conversation;
import com.transferwise.envoy.xds.TypeUrl;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.AssertFactory;
import org.assertj.core.api.InstanceOfAssertFactory;

public class DeltaConversationAssert extends EnvoyAssert<DeltaConversationAssert> {


    public static InstanceOfAssertFactory<Conversation, DeltaConversationAssert> DELTA_CONVERSATION = new InstanceOfAssertFactory<>(Conversation.class, new Factory());

    protected DeltaConversationAssert(Conversation actual) {
        super(actual, DeltaConversationAssert.class);
    }

    public static DeltaConversationAssert assertThat(Conversation actual) {
        return new DeltaConversationAssert(actual);
    }

    @Override
    @CanIgnoreReturnValue
    public DeltaConversationAssert allWereAcked() {
        isNotNull();
        HashSet<String> outstandingNonces = new HashSet<>();

        for (Object message : actual.getMessages()) {
            if (message instanceof DeltaDiscoveryRequest r) {
                if (Strings.emptyToNull(r.getResponseNonce()) != null) {
                    if (!outstandingNonces.remove(r.getResponseNonce())) {
                        failWithMessage("Found unexpected response nonce <%s>, expected one of <%s>", r.getResponseNonce(), outstandingNonces);
                    }
                }
                if (r.hasErrorDetail()) {
                    failWithMessage("Envoy sent error <%s>", r.getErrorDetail().getMessage());
                }
            } else if (message instanceof DeltaDiscoveryResponse r) {
                if (!outstandingNonces.add(r.getNonce())) {
                    failWithMessage("Sent nonce <%s> multiple times", r.getNonce());
                }
            } else {
                failWithMessage("Expected message to be of type DeltaDiscoveryRequest or DeltaDiscoveryResponse, but found <%s>", message.getClass());
            }
        }
        if (!outstandingNonces.isEmpty()) {
            failWithMessage("Found unacked nonces <%s>", outstandingNonces);
        }
        return this;
    }

    @Override
    @CanIgnoreReturnValue
    public DeltaConversationAssert nothingSentUnaskedPre119Reconnect() {
        return nothingSentUnasked(true);
    }

    @Override
    @CanIgnoreReturnValue
    public DeltaConversationAssert nothingSentUnasked() {
        return nothingSentUnasked(false);
    }

    @Override
    @CanIgnoreReturnValue
    public DeltaConversationAssert nothingSentUnasked(boolean allowBrokenReconnectWildcardBehaviourPre119) {
        isNotNull();

        HashMultimap<String, String> subscriptionsByTypeUrl = HashMultimap.create();
        HashSet<String> wildcardSubscriptionTypeUrls = new HashSet<>();
        HashSet<String> seenTypeUrls = new HashSet<>();

        for (Object message : actual.getMessages()) {
            if (message instanceof DeltaDiscoveryRequest r) {
                List<String> addsSubs = r.getResourceNamesSubscribeList();
                if (seenTypeUrls.add(r.getTypeUrl())
                    && (addsSubs.isEmpty() || (TypeUrl.of(r.getTypeUrl()).isWildcard() && allowBrokenReconnectWildcardBehaviourPre119))
                ) {
                    // First request of this type, allow old wildcard behaviour
                    addsSubs = List.of("*");
                }
                for (String addsSub : addsSubs) {
                    if (addsSub.equals("*")) {
                        if (!wildcardSubscriptionTypeUrls.add(r.getTypeUrl())) {
                            failWithMessage("Envoy attempted to subscribe type <%s> as wildcard more than once", r.getTypeUrl());
                        }
                    } else if (!subscriptionsByTypeUrl.put(r.getTypeUrl(), addsSub)) {
                        failWithMessage("Envoy attempted to subscribe type <%s> to resource <%s> more than once", r.getTypeUrl(), addsSub);
                    }

                }
                List<String> removesSubs = r.getResourceNamesUnsubscribeList();
                for (String removeSub : removesSubs) {
                    if (removeSub.equals("*")) {
                        if (!wildcardSubscriptionTypeUrls.remove(r.getTypeUrl())) {
                            failWithMessage("Envoy attempted to unsubscribe type <%s> as wildcard when it was not subscribed", r.getTypeUrl());
                        }
                    } else {
                        if (!subscriptionsByTypeUrl.remove(r.getTypeUrl(), removeSub)) {
                            failWithMessage("Envoy attempted to unsubscribe type <%s> from resource <%s> when it was not subscribed", r.getTypeUrl(), removeSub);
                        }
                    }
                }
            } else if (message instanceof DeltaDiscoveryResponse r) {
                if (!wildcardSubscriptionTypeUrls.contains(r.getTypeUrl())) {
                    if (!subscriptionsByTypeUrl.containsKey(r.getTypeUrl())) {
                        failWithMessage("Sent message for type <%s> which envoy has never asked for", r.getTypeUrl());
                    }
                    List<String> unwanted = r.getResourcesList().stream().map(Resource::getName).filter(resName -> !subscriptionsByTypeUrl.get(r.getTypeUrl()).contains(resName)).toList();
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

    @Override
    @CanIgnoreReturnValue
    public DeltaConversationAssert hadConversationsWith(String... typeUrls) {
        isNotNull();

        Set<String> needed = new HashSet<>(List.of(typeUrls));
        for (Object message : actual.getMessages()) {
            if (message instanceof DeltaDiscoveryRequest r) {
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

    public static class Factory implements AssertFactory<Conversation, DeltaConversationAssert> {

        @Override
        public DeltaConversationAssert createAssert(Conversation conversation) {
            return DeltaConversationAssert.assertThat(conversation);
        }
    }

}
