package com.transferwise.envoy.xds.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.transferwise.envoy.xds.CommonDiscoveryRequest;
import com.transferwise.envoy.xds.DiscoveryService;
import com.transferwise.envoy.xds.NodeConfig;
import com.transferwise.envoy.xds.TypeUrl;
import com.transferwise.envoy.xds.XdsConfig;
import com.transferwise.envoy.xds.api.IncrementalConfigBuilder;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class IncrementalDiscoveryServiceTest {

    private static class DummyUpdate {

    }

    @Test
    public void testUpdateNotSubscribedNoSendUpdates(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                                @Mock IncrementalConfigBuilder<ClusterLoadAssignment, DummyUpdate, Object> configBuilder) {

        final NodeConfig<Object> nodeConfig = NodeConfig.builder().xdsConfig(XdsConfig.builder().clientDetails(new Object()).build()).build();
        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(TypeUrl.EDS, responseObserver, configBuilder, nodeConfig, new SubListSubManager(nodeConfig));
        var initState = new DummyUpdate();

        ds.init(initState);
        ds.onNetworkUpdate(new DummyUpdate());
        ds.sendNetworkUpdatePre();
        ds.sendNetworkUpdatePost();

        // If envoy has never sent a request for EDS, we do not expect any attempt to build a response.
        // If we did return a response due to a leaky config builder (one that returns unasked for resources) then envoy would throw an error.
        verify(responseObserver, never()).onNext(any());
        verifyNoInteractions(configBuilder);
    }

    @Captor ArgumentCaptor<DeltaDiscoveryResponse> responseCaptor;

    @Test
    public void testSubscribeSubListButNoResourcesThenUpdates(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                                @Mock IncrementalConfigBuilder<ClusterLoadAssignment, DummyUpdate, Object> configBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final DummyUpdate next = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder().xdsConfig(XdsConfig.builder().clientDetails(new Object()).build()).build();
        final ClusterLoadAssignment newCluster = ClusterLoadAssignment.newBuilder()
            .setClusterName("foo")
            .build();

        when(configBuilder.getResourcesRemoveOrder(eq(initState), any(), any())).thenReturn(IncrementalConfigBuilder.Resources.<ClusterLoadAssignment>builder().build());
        when(configBuilder.addOrder(eq(next), any(), any())).thenReturn(IncrementalConfigBuilder.Response.<ClusterLoadAssignment>builder()
            .addAndUpdate(IncrementalConfigBuilder.NamedMessage.of(newCluster))
            .build());
        when(configBuilder.removeOrder(eq(next), any(), any())).thenReturn(IncrementalConfigBuilder.Response.<ClusterLoadAssignment>builder().build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(TypeUrl.EDS, responseObserver, configBuilder, nodeConfig, new SubListSubManager(nodeConfig));

        // Envoy sends an EDS request:
        ds.init(initState);
        assertThat(ds.awaitingAck()).isFalse();
        ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(TypeUrl.EDS.getTypeUrl())
            .message(DeltaDiscoveryRequest.newBuilder()
                .setTypeUrl(TypeUrl.EDS.getTypeUrl())
                .addResourceNamesSubscribe("foo")
                .build())
            .build());

        InOrder inOrder = inOrder(responseObserver);
        inOrder.verify(responseObserver).onNext(responseCaptor.capture());

        // Our initial state has no resources, so we should tell envoy that the resource (foo) that it asked for has been removed.
        DeltaDiscoveryResponse resp = responseCaptor.getValue();
        final String nonce = resp.getNonce();
        assertThat(resp.getRemovedResourcesList()).containsExactly("foo");
        assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.EDS.getTypeUrl());
        assertThat(resp.getResourcesList()).isEmpty();

        assertThat(ds.awaitingAck()).isTrue();

        // Envoy acks the update, without changing the subs list.
        ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(TypeUrl.EDS.getTypeUrl())
            .message(DeltaDiscoveryRequest.newBuilder()
                .setTypeUrl(TypeUrl.EDS.getTypeUrl())
                .setResponseNonce(nonce)
                .build())
            .build());

        assertThat(ds.awaitingAck()).isFalse();

        ds.onNetworkUpdate(next);
        ds.sendNetworkUpdatePre();
        ds.sendNetworkUpdatePost();

        inOrder.verify(responseObserver).onNext(responseCaptor.capture());

        resp = responseCaptor.getValue();
        assertThat(resp.getRemovedResourcesList()).isEmpty();
        assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.EDS.getTypeUrl());
        assertThat(resp.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("foo");

        assertThat(ds.awaitingAck()).isTrue();
    }

    @Test
    public void testSubscribedToNothing(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                                              @Mock IncrementalConfigBuilder<ClusterLoadAssignment, DummyUpdate, Object> configBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder().xdsConfig(XdsConfig.builder().clientDetails(new Object()).build()).build();

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(TypeUrl.EDS, responseObserver, configBuilder, nodeConfig, new SubListSubManager(nodeConfig));

        ds.init(initState);
        assertThat(ds.awaitingAck()).isFalse();

        // Envoy sends an EDS request that asks for nothing
        ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(TypeUrl.EDS.getTypeUrl())
            .message(DeltaDiscoveryRequest.newBuilder()
                .setTypeUrl(TypeUrl.EDS.getTypeUrl())
                .build())
            .build());

        // Send it again to see what happens
        ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(TypeUrl.EDS.getTypeUrl())
            .message(DeltaDiscoveryRequest.newBuilder()
                .setTypeUrl(TypeUrl.EDS.getTypeUrl())
                .build())
            .build());

        // If envoy didn't ask for anything, we do not expect any attempt to send a response? Or do we? I have no idea!
        verify(responseObserver, never()).onNext(any());
        assertThat(ds.awaitingAck()).isFalse();
        verifyNoInteractions(configBuilder);
    }

    @Test
    public void testOldWildcardSubNoState(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                   @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> configBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder().xdsConfig(XdsConfig.builder().clientDetails(new Object()).build()).build();

        when(configBuilder.getResourcesRemoveOrder(eq(initState), any(), any())).thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(TypeUrl.CDS, responseObserver, configBuilder, nodeConfig, new WildcardSubManager(nodeConfig));

        ds.init(initState);
        assertThat(ds.awaitingAck()).isFalse();

        // Envoy sends a CDS request that asks for nothing, this should cause it to sub to wildcard.
        ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(TypeUrl.CDS.getTypeUrl())
            .message(DeltaDiscoveryRequest.newBuilder()
                .setTypeUrl(TypeUrl.CDS.getTypeUrl())
                .build())
            .build());

        verify(responseObserver).onNext(responseCaptor.capture());
        // We should tell envoy about everything
        DeltaDiscoveryResponse resp = responseCaptor.getValue();
        assertThat(resp.getResourcesList()).isEmpty();
        assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(resp.getRemovedResourcesList()).isEmpty();

        assertThat(ds.awaitingAck()).isTrue();
    }

    @Test
    public void testOldWildcardSub(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                   @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> configBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder().xdsConfig(XdsConfig.builder().clientDetails(new Object()).build()).build();

        final Cluster newCluster = Cluster.newBuilder()
            .setName("foo")
            .build();

        when(configBuilder.getResourcesRemoveOrder(eq(initState), any(), any())).thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().resource(IncrementalConfigBuilder.NamedMessage.of(newCluster)).build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(TypeUrl.CDS, responseObserver, configBuilder, nodeConfig, new WildcardSubManager(nodeConfig));

        ds.init(initState);
        assertThat(ds.awaitingAck()).isFalse();

        // Envoy sends a CDS request that asks for nothing, this should cause it to sub to wildcard.
        ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(TypeUrl.CDS.getTypeUrl())
            .message(DeltaDiscoveryRequest.newBuilder()
                .setTypeUrl(TypeUrl.CDS.getTypeUrl())
                .build())
            .build());

        verify(responseObserver).onNext(responseCaptor.capture());
        // We should tell envoy about everything
        DeltaDiscoveryResponse resp = responseCaptor.getValue();
        assertThat(resp.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("foo");
        assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(resp.getRemovedResourcesList()).isEmpty();

        assertThat(ds.awaitingAck()).isTrue();
    }

    @Test
    public void testAskingAgain(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                   @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> configBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder().xdsConfig(XdsConfig.builder().clientDetails(new Object()).build()).build();

        final Cluster newCluster = Cluster.newBuilder()
            .setName("foo")
            .build();

        when(configBuilder.getResourcesRemoveOrder(eq(initState), any(), any())).thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().resource(IncrementalConfigBuilder.NamedMessage.of(newCluster)).build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(TypeUrl.EDS, responseObserver, configBuilder, nodeConfig, new SubListSubManager(nodeConfig));

        ds.init(initState);
        assertThat(ds.awaitingAck()).isFalse();

        String n;
        InOrder inOrder = inOrder(responseObserver);

        {
            ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
                .typeUrl(TypeUrl.EDS.getTypeUrl())
                .message(DeltaDiscoveryRequest.newBuilder()
                    .setTypeUrl(TypeUrl.EDS.getTypeUrl())
                    .addResourceNamesSubscribe("foo")
                    .build())
                .build());

            inOrder.verify(responseObserver).onNext(responseCaptor.capture());

            DeltaDiscoveryResponse resp = responseCaptor.getValue();
            assertThat(resp.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("foo");
            assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.EDS.getTypeUrl());
            assertThat(resp.getRemovedResourcesList()).isEmpty();
            n = resp.getNonce();

            assertThat(ds.awaitingAck()).isTrue();
        }

        {
            // Ask again, as if envoy has forgotten about the resource
            ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
                .typeUrl(TypeUrl.EDS.getTypeUrl())
                .message(DeltaDiscoveryRequest.newBuilder()
                    .setTypeUrl(TypeUrl.EDS.getTypeUrl())
                    .addResourceNamesSubscribe("foo")
                    .build())
                .build());

            inOrder.verify(responseObserver).onNext(responseCaptor.capture());
            DeltaDiscoveryResponse resp = responseCaptor.getValue();
            assertThat(resp.getNonce()).isNotEqualTo(n); // Validate we captured the right response and aren't just verifying the first one again!
            assertThat(resp.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("foo");
            assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.EDS.getTypeUrl());
            assertThat(resp.getRemovedResourcesList()).isEmpty();


            assertThat(ds.awaitingAck()).isTrue();
        }
    }

    @Test
    public void testNewWildcardSub(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
                                   @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> configBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder().xdsConfig(XdsConfig.builder().clientDetails(new Object()).build()).build();

        final Cluster newCluster = Cluster.newBuilder()
            .setName("foo")
            .build();

        when(configBuilder.getResourcesRemoveOrder(eq(initState), any(), any())).thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().resource(IncrementalConfigBuilder.NamedMessage.of(newCluster)).build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(TypeUrl.CDS, responseObserver, configBuilder, nodeConfig, new WildcardSubManager(nodeConfig));

        ds.init(initState);
        assertThat(ds.awaitingAck()).isFalse();

        // Envoy sends a CDS request that asks for "*", this should cause it to sub to wildcard.
        ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(TypeUrl.CDS.getTypeUrl())
            .message(DeltaDiscoveryRequest.newBuilder()
                .setTypeUrl(TypeUrl.CDS.getTypeUrl())
                .addResourceNamesSubscribe("*")
                .build())
            .build());

        verify(responseObserver).onNext(responseCaptor.capture());
        // We should tell envoy about everything
        DeltaDiscoveryResponse resp = responseCaptor.getValue();
        assertThat(resp.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("foo");
        assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(resp.getRemovedResourcesList()).isEmpty();

        assertThat(ds.awaitingAck()).isTrue();
    }

    @Test
    public void testRemovesInitialStateIfNotExists(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
        @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> configBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder().xdsConfig(XdsConfig.builder().clientDetails(new Object()).build()).build();

        final Cluster newCluster = Cluster.newBuilder()
            .setName("foo")
            .build();

        when(configBuilder.getResourcesRemoveOrder(eq(initState), any(), any())).thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().resource(IncrementalConfigBuilder.NamedMessage.of(newCluster)).build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(TypeUrl.CDS, responseObserver, configBuilder, nodeConfig, new WildcardSubManager(nodeConfig));

        ds.init(initState);
        assertThat(ds.awaitingAck()).isFalse();

        // Envoy sends a CDS request that asks for "*", this should cause it to sub to wildcard.
        // This time we also tell envoy we already now about foo, bar and baz - which is what happens when envoy already has state when it connects to ADS (e.g. it's reconnecting.)
        ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(TypeUrl.CDS.getTypeUrl())
            .message(DeltaDiscoveryRequest.newBuilder()
                .setTypeUrl(TypeUrl.CDS.getTypeUrl())
                .addResourceNamesSubscribe("*")
                .putInitialResourceVersions("foo", "1")
                .putInitialResourceVersions("bar", "1")
                .putInitialResourceVersions("baz", "1")
                .build())
            .build());

        verify(responseObserver).onNext(responseCaptor.capture());
        // We should tell envoy about everything we know about (which is just foo)
        DeltaDiscoveryResponse resp = responseCaptor.getValue();
        assertThat(resp.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("foo");
        assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        // Since bar and baz don't exist, they must have been removed since this client last got a state update (before it lost connection to the ADS.)
        // Tell it to delete them.
        assertThat(resp.getRemovedResourcesList()).containsExactlyInAnyOrder("bar", "baz");

        assertThat(ds.awaitingAck()).isTrue();
    }

    @Test
    public void testDoesNotRemoveInitialStateIfNotSubscribed(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
        @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> configBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder().xdsConfig(XdsConfig.builder().clientDetails(new Object()).build()).build();

        final Cluster newCluster = Cluster.newBuilder()
            .setName("foo")
            .build();

        when(configBuilder.getResourcesRemoveOrder(eq(initState), any(), any())).thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().resource(IncrementalConfigBuilder.NamedMessage.of(newCluster)).build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(TypeUrl.CDS, responseObserver, configBuilder, nodeConfig, new WildcardSubManager(nodeConfig));

        ds.init(initState);
        assertThat(ds.awaitingAck()).isFalse();

        // Envoy sends a CDS request that asks for "foo", this should cause it to sub to the specific resource.
        // This time we also tell envoy we already now about foo, bar and baz - which is what happens when envoy already has state when it connects to ADS (e.g. it's reconnecting.)
        ds.processUpdate(CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(TypeUrl.CDS.getTypeUrl())
            .message(DeltaDiscoveryRequest.newBuilder()
                .setTypeUrl(TypeUrl.CDS.getTypeUrl())
                .addResourceNamesSubscribe("foo")
                .addResourceNamesSubscribe("bar")
                .putInitialResourceVersions("foo", "1")
                .putInitialResourceVersions("bar", "1")
                .putInitialResourceVersions("baz", "1")
                .build())
            .build());

        verify(responseObserver).onNext(responseCaptor.capture());
        // We should tell envoy about the thing it asked for that still exists (foo)
        DeltaDiscoveryResponse resp = responseCaptor.getValue();
        assertThat(resp.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("foo");
        assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        // Envoy asked for bar, but it doesn't exist. While baz exists in the initial versions, the client has not asked to subscribe to it, so we don't send a remove.
        assertThat(resp.getRemovedResourcesList()).containsOnly("bar");

        assertThat(ds.awaitingAck()).isTrue();
    }

}
