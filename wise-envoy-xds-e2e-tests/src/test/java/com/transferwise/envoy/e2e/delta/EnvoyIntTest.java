package com.transferwise.envoy.e2e.delta;

import static com.transferwise.envoy.e2e.assertions.DeltaConversationAssert.DELTA_CONVERSATION;
import static com.transferwise.envoy.e2e.configdump.EnvoyAdminClient.sneakyUnpack;
import static org.assertj.core.api.Assertions.assertThat;

import com.transferwise.envoy.e2e.BaseEnvoyIntTest;
import com.transferwise.envoy.e2e.assertions.DeltaConversationAssert;
import com.transferwise.envoy.e2e.utils.ConversationLogger.Conversation;
import com.transferwise.envoy.xds.TypeUrl;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;
import org.assertj.core.api.InstanceOfAssertFactory;
import org.junit.jupiter.api.Test;


public abstract class EnvoyIntTest extends BaseEnvoyIntTest<DeltaConversationAssert> {

    @Override
    protected String getEnvoyConfigFileName() {
        return "envoy-delta-1.17.yaml";
    }

    @Override
    protected InstanceOfAssertFactory<Conversation, DeltaConversationAssert> assertFactory() {
        return DELTA_CONVERSATION;
    }

    @Override
    protected DeltaConversationAssert envoyAssertThat(Conversation conversation) {
        return DeltaConversationAssert.assertThat(conversation);
    }

    @Test
    public void reconnectRemovalOrdering() throws IOException, InterruptedException {
        Conversation reconnect = reconnectAfterRemovingBar(TypeUrl.RDS);
        List<Object> messages = reconnect.getMessages();

        int firstCdsResponse = indexOfDeltaResponse(messages, TypeUrl.CDS, response -> true);
        assertThat(deltaResponseAt(messages, firstCdsResponse).getRemovedResourcesList()).doesNotContain("bar");

        int rdsWithoutBar = indexOfDeltaResponse(messages, TypeUrl.RDS, response -> routeResponseDoesNotReferenceCluster(response, "bar"));
        int rdsAck = indexOfDeltaAckForResponse(messages, rdsWithoutBar);
        int cdsRemoval = indexOfDeltaResponse(messages, TypeUrl.CDS, response -> response.getRemovedResourcesList().contains("bar"));
        int edsRemoval = optionalIndexOfDeltaResponse(messages, TypeUrl.EDS, response -> response.getRemovedResourcesList().contains("bar"));

        assertThat(rdsAck).isLessThan(cdsRemoval);
        if (edsRemoval != -1) {
            assertThat(cdsRemoval).isLessThan(edsRemoval);
        }
    }

    private int indexOfDeltaResponse(List<Object> messages, TypeUrl typeUrl, Predicate<DeltaDiscoveryResponse> predicate) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof DeltaDiscoveryResponse response
                && response.getTypeUrl().equals(typeUrl.getTypeUrl())
                && predicate.test(response)) {
                return i;
            }
        }
        throw new AssertionError("Did not find " + typeUrl + " DeltaDiscoveryResponse matching predicate");
    }

    private int optionalIndexOfDeltaResponse(List<Object> messages, TypeUrl typeUrl, Predicate<DeltaDiscoveryResponse> predicate) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof DeltaDiscoveryResponse response
                && response.getTypeUrl().equals(typeUrl.getTypeUrl())
                && predicate.test(response)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfDeltaAckForResponse(List<Object> messages, int responseIndex) {
        DeltaDiscoveryResponse response = deltaResponseAt(messages, responseIndex);
        for (int i = responseIndex + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof DeltaDiscoveryRequest request
                && request.getTypeUrl().equals(response.getTypeUrl())
                && request.getResponseNonce().equals(response.getNonce())) {
                return i;
            }
        }
        throw new AssertionError("Did not find ACK for " + response.getTypeUrl() + " nonce " + response.getNonce());
    }

    private DeltaDiscoveryResponse deltaResponseAt(List<Object> messages, int index) {
        return (DeltaDiscoveryResponse) messages.get(index);
    }

    private boolean routeResponseDoesNotReferenceCluster(DeltaDiscoveryResponse response, String clusterName) {
        List<RouteConfiguration> routeConfigurations = response.getResourcesList().stream()
            .map(resource -> sneakyUnpack(resource.getResource(), RouteConfiguration.class))
            .toList();

        return !routeConfigurations.isEmpty()
            && routeConfigurations.stream()
                .noneMatch(routeConfiguration -> routeConfigReferencesCluster(routeConfiguration, clusterName));
    }

    private boolean routeConfigReferencesCluster(RouteConfiguration routeConfiguration, String clusterName) {
        return routeConfiguration.getVirtualHostsList().stream()
            .flatMap(virtualHost -> virtualHost.getRoutesList().stream())
            .filter(route -> route.hasRoute())
            .anyMatch(route -> route.getRoute().getCluster().equals(clusterName));
    }
}
