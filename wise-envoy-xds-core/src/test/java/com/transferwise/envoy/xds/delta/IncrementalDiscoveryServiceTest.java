package com.transferwise.envoy.xds.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.transferwise.envoy.xds.CommonDiscoveryRequest;
import com.transferwise.envoy.xds.DiscoveryService;
import com.transferwise.envoy.xds.DiscoveryServiceManager;
import com.transferwise.envoy.xds.NodeConfig;
import com.transferwise.envoy.xds.TypeUrl;
import com.transferwise.envoy.xds.XdsConfig;
import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;
import com.transferwise.envoy.xds.api.IncrementalConfigBuilder;
import com.transferwise.envoy.xds.api.utils.QueueingStateBacklog;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
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
        // With no manager-level ACK barrier configured, stale resources from initial_resource_versions are removed
        // in the initial subscription response.
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
        // Envoy asked for bar, and reported that it already knows bar in initial_resource_versions.
        // With no manager-level ACK barrier configured, that stale subscribed resource is removed immediately.
        // Baz exists in the initial versions, but the client has not asked to subscribe to it, so we don't remove it either.
        assertThat(resp.getRemovedResourcesList()).containsExactly("bar");

        assertThat(ds.awaitingAck()).isTrue();
    }

    @Test
    public void testReconnectInitialResourceVersionRemovalsWaitForConfiguredAck(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
        @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> clusterConfigBuilder,
        @Mock IncrementalConfigBuilder<RouteConfiguration, DummyUpdate, Object> routeConfigBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder()
            .xdsConfig(XdsConfig.builder().clientDetails(new Object()).build())
            .build();

        final RouteConfiguration routeWithoutDeletedCluster = RouteConfiguration.newBuilder()
            .setName("route-x")
            .build();

        when(clusterConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().build());
        when(routeConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<RouteConfiguration>builder()
                .resource(IncrementalConfigBuilder.NamedMessage.of(routeWithoutDeletedCluster))
                .build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> cds = new IncrementalDiscoveryService<>(
            TypeUrl.CDS,
            responseObserver,
            clusterConfigBuilder,
            nodeConfig,
            new WildcardSubManager(nodeConfig)
        );
        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> rds = new IncrementalDiscoveryService<>(
            TypeUrl.RDS,
            responseObserver,
            routeConfigBuilder,
            nodeConfig,
            new SubListSubManager(nodeConfig)
        );
        DiscoveryServiceManager<DeltaDiscoveryRequest, DummyUpdate> discoveryServiceManager = new DiscoveryServiceManager<>(
            Map.of(TypeUrl.CDS, cds, TypeUrl.RDS, rds),
            List.of(TypeUrl.CDS, TypeUrl.RDS),
            List.of(TypeUrl.RDS, TypeUrl.CDS),
            QueueingStateBacklog.<DummyUpdate>factory().build(),
            DiscoveryServiceManagerMetrics.NOOP_METRICS
        );

        discoveryServiceManager.init(initState, TypeUrl.RDS);
        InOrder inOrder = inOrder(responseObserver);

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.CDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.CDS.getTypeUrl())
            .addResourceNamesSubscribe("*")
            .putInitialResourceVersions("cluster-a", "1")
            .build()));

        inOrder.verify(responseObserver).onNext(responseCaptor.capture());
        DeltaDiscoveryResponse cdsReconnectResponse = responseCaptor.getValue();
        assertThat(cdsReconnectResponse.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(cdsReconnectResponse.getResourcesList()).isEmpty();
        assertThat(cdsReconnectResponse.getRemovedResourcesList()).isEmpty();

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.CDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.CDS.getTypeUrl())
            .setResponseNonce(cdsReconnectResponse.getNonce())
            .build()));

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.RDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.RDS.getTypeUrl())
            .addResourceNamesSubscribe("route-x")
            .putInitialResourceVersions("route-x", "1")
            .build()));

        inOrder.verify(responseObserver).onNext(responseCaptor.capture());
        DeltaDiscoveryResponse rdsReconnectResponse = responseCaptor.getValue();
        assertThat(rdsReconnectResponse.getTypeUrl()).isEqualTo(TypeUrl.RDS.getTypeUrl());
        assertThat(rdsReconnectResponse.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("route-x");
        assertThat(rdsReconnectResponse.getRemovedResourcesList()).isEmpty();

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.RDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.RDS.getTypeUrl())
            .setResponseNonce(rdsReconnectResponse.getNonce())
            .build()));

        inOrder.verify(responseObserver).onNext(responseCaptor.capture());
        DeltaDiscoveryResponse cdsDeferredRemovalResponse = responseCaptor.getValue();
        assertThat(cdsDeferredRemovalResponse.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(cdsDeferredRemovalResponse.getResourcesList()).isEmpty();
        assertThat(cdsDeferredRemovalResponse.getRemovedResourcesList()).containsExactly("cluster-a");
    }

    @Test
    public void testNoConfiguredDelaySendsInitialResourceVersionRemovalsInInitialResponse(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
        @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> clusterConfigBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder()
            .xdsConfig(XdsConfig.builder().clientDetails(new Object()).build())
            .build();

        when(clusterConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> cds = new IncrementalDiscoveryService<>(
            TypeUrl.CDS,
            responseObserver,
            clusterConfigBuilder,
            nodeConfig,
            new WildcardSubManager(nodeConfig)
        );
        DiscoveryServiceManager<DeltaDiscoveryRequest, DummyUpdate> discoveryServiceManager = new DiscoveryServiceManager<>(
            Map.of(TypeUrl.CDS, cds),
            List.of(TypeUrl.CDS),
            List.of(TypeUrl.CDS),
            QueueingStateBacklog.<DummyUpdate>factory().build(),
            DiscoveryServiceManagerMetrics.NOOP_METRICS
        );

        discoveryServiceManager.init(initState);
        InOrder inOrder = inOrder(responseObserver);

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.CDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.CDS.getTypeUrl())
            .addResourceNamesSubscribe("*")
            .putInitialResourceVersions("cluster-a", "1")
            .build()));

        DeltaDiscoveryResponse reconnectResponse = nextResponse(inOrder, responseObserver);
        assertThat(reconnectResponse.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(reconnectResponse.getResourcesList()).isEmpty();
        assertThat(reconnectResponse.getRemovedResourcesList()).containsExactly("cluster-a");
        assertThat(cds.hasDeferredReconnectRemovals()).isFalse();

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.CDS, reconnectResponse.getNonce()));
        assertNoAdditionalResponsesSent(responseObserver, 1);
    }

    @Test
    public void testEarlyConfiguredDelayCanSendRemovalsBeforeLaterSubscriptionsButStillProgresses(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
        @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> clusterConfigBuilder,
        @Mock IncrementalConfigBuilder<RouteConfiguration, DummyUpdate, Object> routeConfigBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder()
            .xdsConfig(XdsConfig.builder().clientDetails(new Object()).build())
            .build();

        when(clusterConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().build());
        when(routeConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<RouteConfiguration>builder().build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> cds = new IncrementalDiscoveryService<>(
            TypeUrl.CDS,
            responseObserver,
            clusterConfigBuilder,
            nodeConfig,
            new WildcardSubManager(nodeConfig)
        );
        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> rds = new IncrementalDiscoveryService<>(
            TypeUrl.RDS,
            responseObserver,
            routeConfigBuilder,
            nodeConfig,
            new SubListSubManager(nodeConfig)
        );
        DiscoveryServiceManager<DeltaDiscoveryRequest, DummyUpdate> discoveryServiceManager = new DiscoveryServiceManager<>(
            Map.of(TypeUrl.CDS, cds, TypeUrl.RDS, rds),
            List.of(TypeUrl.CDS, TypeUrl.RDS),
            List.of(TypeUrl.RDS, TypeUrl.CDS),
            QueueingStateBacklog.<DummyUpdate>factory().build(),
            DiscoveryServiceManagerMetrics.NOOP_METRICS
        );

        discoveryServiceManager.init(initState, TypeUrl.CDS);
        InOrder inOrder = inOrder(responseObserver);

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.CDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.CDS.getTypeUrl())
            .addResourceNamesSubscribe("*")
            .putInitialResourceVersions("cluster-stale", "1")
            .build()));
        DeltaDiscoveryResponse cdsReconnectResponse = nextResponse(inOrder, responseObserver);
        assertThat(cdsReconnectResponse.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(cdsReconnectResponse.getRemovedResourcesList()).isEmpty();

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.CDS, cdsReconnectResponse.getNonce()));

        DeltaDiscoveryResponse cdsRemovalResponse = nextResponse(inOrder, responseObserver);
        assertThat(cdsRemovalResponse.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(cdsRemovalResponse.getRemovedResourcesList()).containsExactly("cluster-stale");

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.CDS, cdsRemovalResponse.getNonce()));
        assertNoAdditionalResponsesSent(responseObserver, 2);

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.RDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.RDS.getTypeUrl())
            .addResourceNamesSubscribe("route-stale")
            .putInitialResourceVersions("route-stale", "1")
            .build()));
        DeltaDiscoveryResponse rdsReconnectResponse = nextResponse(inOrder, responseObserver);
        assertThat(rdsReconnectResponse.getTypeUrl()).isEqualTo(TypeUrl.RDS.getTypeUrl());
        assertThat(rdsReconnectResponse.getRemovedResourcesList()).isEmpty();

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.RDS, rdsReconnectResponse.getNonce()));

        DeltaDiscoveryResponse rdsRemovalResponse = nextResponse(inOrder, responseObserver);
        assertThat(rdsRemovalResponse.getTypeUrl()).isEqualTo(TypeUrl.RDS.getTypeUrl());
        assertThat(rdsRemovalResponse.getRemovedResourcesList()).containsExactly("route-stale");
    }

    @Test
    public void testReconnectNetworkUpdatesWaitForDeferredInitialRemovals(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
        @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> clusterConfigBuilder,
        @Mock IncrementalConfigBuilder<RouteConfiguration, DummyUpdate, Object> routeConfigBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final DummyUpdate nextState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder()
            .xdsConfig(XdsConfig.builder().clientDetails(new Object()).build())
            .build();

        final RouteConfiguration routeWithoutDeletedCluster = RouteConfiguration.newBuilder()
            .setName("route-x")
            .build();
        final Cluster newCluster = Cluster.newBuilder()
            .setName("cluster-b")
            .build();

        when(clusterConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().build());
        when(routeConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<RouteConfiguration>builder()
                .resource(IncrementalConfigBuilder.NamedMessage.of(routeWithoutDeletedCluster))
                .build());
        when(clusterConfigBuilder.addOrder(eq(nextState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Response.<Cluster>builder()
                .addAndUpdate(IncrementalConfigBuilder.NamedMessage.of(newCluster))
                .build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> cds = new IncrementalDiscoveryService<>(
            TypeUrl.CDS,
            responseObserver,
            clusterConfigBuilder,
            nodeConfig,
            new WildcardSubManager(nodeConfig)
        );
        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> rds = new IncrementalDiscoveryService<>(
            TypeUrl.RDS,
            responseObserver,
            routeConfigBuilder,
            nodeConfig,
            new SubListSubManager(nodeConfig)
        );
        DiscoveryServiceManager<DeltaDiscoveryRequest, DummyUpdate> discoveryServiceManager = new DiscoveryServiceManager<>(
            Map.of(TypeUrl.CDS, cds, TypeUrl.RDS, rds),
            List.of(TypeUrl.CDS, TypeUrl.RDS),
            List.of(TypeUrl.RDS, TypeUrl.CDS),
            QueueingStateBacklog.<DummyUpdate>factory().build(),
            DiscoveryServiceManagerMetrics.NOOP_METRICS
        );

        discoveryServiceManager.init(initState, TypeUrl.RDS);
        InOrder inOrder = inOrder(responseObserver);

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.CDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.CDS.getTypeUrl())
            .addResourceNamesSubscribe("*")
            .putInitialResourceVersions("cluster-a", "1")
            .build()));
        DeltaDiscoveryResponse cdsReconnectResponse = nextResponse(inOrder, responseObserver);
        assertThat(cdsReconnectResponse.getRemovedResourcesList()).isEmpty();

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.CDS, cdsReconnectResponse.getNonce()));

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.RDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.RDS.getTypeUrl())
            .addResourceNamesSubscribe("route-x")
            .putInitialResourceVersions("route-x", "1")
            .build()));
        DeltaDiscoveryResponse rdsReconnectResponse = nextResponse(inOrder, responseObserver);
        assertThat(rdsReconnectResponse.getTypeUrl()).isEqualTo(TypeUrl.RDS.getTypeUrl());
        assertThat(rdsReconnectResponse.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("route-x");
        assertThat(rdsReconnectResponse.getRemovedResourcesList()).isEmpty();

        discoveryServiceManager.pushUpdates(nextState);
        verify(responseObserver, times(2)).onNext(any());

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.RDS, rdsReconnectResponse.getNonce()));

        DeltaDiscoveryResponse cdsDeferredRemovalResponse = nextResponse(inOrder, responseObserver);
        assertThat(cdsDeferredRemovalResponse.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(cdsDeferredRemovalResponse.getResourcesList()).isEmpty();
        assertThat(cdsDeferredRemovalResponse.getRemovedResourcesList()).containsExactly("cluster-a");

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.CDS, cdsDeferredRemovalResponse.getNonce()));

        DeltaDiscoveryResponse cdsNetworkUpdateResponse = nextResponse(inOrder, responseObserver);
        assertThat(cdsNetworkUpdateResponse.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(cdsNetworkUpdateResponse.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("cluster-b");
        assertThat(cdsNetworkUpdateResponse.getRemovedResourcesList()).isEmpty();
    }

    @Test
    public void testDeferredReconnectRemovalsUseRemoveOrderAndWaitForAck(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
        @Mock IncrementalConfigBuilder<Cluster, DummyUpdate, Object> clusterConfigBuilder,
        @Mock IncrementalConfigBuilder<ClusterLoadAssignment, DummyUpdate, Object> endpointConfigBuilder,
        @Mock IncrementalConfigBuilder<RouteConfiguration, DummyUpdate, Object> routeConfigBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder()
            .xdsConfig(XdsConfig.builder().clientDetails(new Object()).build())
            .build();

        final RouteConfiguration currentRoute = RouteConfiguration.newBuilder()
            .setName("route-current")
            .build();

        when(routeConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<RouteConfiguration>builder()
                .resource(IncrementalConfigBuilder.NamedMessage.of(currentRoute))
                .build());
        when(clusterConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<Cluster>builder().build());
        when(endpointConfigBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<ClusterLoadAssignment>builder().build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> cds = new IncrementalDiscoveryService<>(
            TypeUrl.CDS,
            responseObserver,
            clusterConfigBuilder,
            nodeConfig,
            new WildcardSubManager(nodeConfig)
        );
        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> eds = new IncrementalDiscoveryService<>(
            TypeUrl.EDS,
            responseObserver,
            endpointConfigBuilder,
            nodeConfig,
            new SubListSubManager(nodeConfig)
        );
        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> rds = new IncrementalDiscoveryService<>(
            TypeUrl.RDS,
            responseObserver,
            routeConfigBuilder,
            nodeConfig,
            new SubListSubManager(nodeConfig)
        );
        DiscoveryServiceManager<DeltaDiscoveryRequest, DummyUpdate> discoveryServiceManager = new DiscoveryServiceManager<>(
            Map.of(TypeUrl.CDS, cds, TypeUrl.EDS, eds, TypeUrl.RDS, rds),
            List.of(TypeUrl.CDS, TypeUrl.EDS, TypeUrl.RDS),
            List.of(TypeUrl.RDS, TypeUrl.CDS, TypeUrl.EDS),
            QueueingStateBacklog.<DummyUpdate>factory().build(),
            DiscoveryServiceManagerMetrics.NOOP_METRICS
        );

        discoveryServiceManager.init(initState, TypeUrl.RDS);
        InOrder inOrder = inOrder(responseObserver);

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.CDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.CDS.getTypeUrl())
            .addResourceNamesSubscribe("*")
            .putInitialResourceVersions("cluster-stale", "1")
            .build()));
        DeltaDiscoveryResponse cdsReconnectResponse = nextResponse(inOrder, responseObserver);
        assertThat(cdsReconnectResponse.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(cdsReconnectResponse.getResourcesList()).isEmpty();
        assertThat(cdsReconnectResponse.getRemovedResourcesList()).isEmpty();
        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.CDS, cdsReconnectResponse.getNonce()));

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.EDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.EDS.getTypeUrl())
            .addResourceNamesSubscribe("endpoint-stale")
            .putInitialResourceVersions("endpoint-stale", "1")
            .build()));
        DeltaDiscoveryResponse edsReconnectResponse = nextResponse(inOrder, responseObserver);
        assertThat(edsReconnectResponse.getTypeUrl()).isEqualTo(TypeUrl.EDS.getTypeUrl());
        assertThat(edsReconnectResponse.getResourcesList()).isEmpty();
        assertThat(edsReconnectResponse.getRemovedResourcesList()).isEmpty();
        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.EDS, edsReconnectResponse.getNonce()));

        discoveryServiceManager.processUpdate(deltaRequest(TypeUrl.RDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.RDS.getTypeUrl())
            .addResourceNamesSubscribe("route-current")
            .addResourceNamesSubscribe("route-stale")
            .putInitialResourceVersions("route-stale", "1")
            .build()));
        DeltaDiscoveryResponse rdsReconnectResponse = nextResponse(inOrder, responseObserver);
        assertThat(rdsReconnectResponse.getTypeUrl()).isEqualTo(TypeUrl.RDS.getTypeUrl());
        assertThat(rdsReconnectResponse.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("route-current");
        assertThat(rdsReconnectResponse.getRemovedResourcesList()).isEmpty();

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.RDS, rdsReconnectResponse.getNonce()));

        DeltaDiscoveryResponse rdsRemovalResponse = nextResponse(inOrder, responseObserver);
        assertThat(rdsRemovalResponse.getTypeUrl()).isEqualTo(TypeUrl.RDS.getTypeUrl());
        assertThat(rdsRemovalResponse.getResourcesList()).isEmpty();
        assertThat(rdsRemovalResponse.getRemovedResourcesList()).containsExactly("route-stale");
        assertNoAdditionalResponsesSent(responseObserver, 4);

        discoveryServiceManager.processUpdate(nonAckRequest(TypeUrl.RDS));
        assertNoAdditionalResponsesSent(responseObserver, 4);

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.RDS, rdsRemovalResponse.getNonce()));

        DeltaDiscoveryResponse cdsRemovalResponse = nextResponse(inOrder, responseObserver);
        assertThat(cdsRemovalResponse.getTypeUrl()).isEqualTo(TypeUrl.CDS.getTypeUrl());
        assertThat(cdsRemovalResponse.getResourcesList()).isEmpty();
        assertThat(cdsRemovalResponse.getRemovedResourcesList()).containsExactly("cluster-stale");
        assertNoAdditionalResponsesSent(responseObserver, 5);

        discoveryServiceManager.processUpdate(nonAckRequest(TypeUrl.CDS));
        assertNoAdditionalResponsesSent(responseObserver, 5);

        discoveryServiceManager.processUpdate(ackRequest(TypeUrl.CDS, cdsRemovalResponse.getNonce()));

        DeltaDiscoveryResponse edsRemovalResponse = nextResponse(inOrder, responseObserver);
        assertThat(edsRemovalResponse.getTypeUrl()).isEqualTo(TypeUrl.EDS.getTypeUrl());
        assertThat(edsRemovalResponse.getResourcesList()).isEmpty();
        assertThat(edsRemovalResponse.getRemovedResourcesList()).containsExactly("endpoint-stale");
    }

    @Test
    public void testDoesNotDeferInitialStateRemovalForUnsubscribedSubListResource(@Mock StreamObserver<DeltaDiscoveryResponse> responseObserver,
        @Mock IncrementalConfigBuilder<ClusterLoadAssignment, DummyUpdate, Object> configBuilder) {

        final DummyUpdate initState = new DummyUpdate();
        final NodeConfig<Object> nodeConfig = NodeConfig.builder()
            .xdsConfig(XdsConfig.builder().clientDetails(new Object()).build())
            .build();
        final ClusterLoadAssignment currentEndpoint = ClusterLoadAssignment.newBuilder()
            .setClusterName("endpoint-current")
            .build();

        when(configBuilder.getResourcesRemoveOrder(eq(initState), any(), any()))
            .thenReturn(IncrementalConfigBuilder.Resources.<ClusterLoadAssignment>builder()
                .resource(IncrementalConfigBuilder.NamedMessage.of(currentEndpoint))
                .build());

        DiscoveryService<DeltaDiscoveryRequest, DummyUpdate> ds = new IncrementalDiscoveryService<>(
            TypeUrl.EDS,
            responseObserver,
            configBuilder,
            nodeConfig,
            new SubListSubManager(nodeConfig)
        );

        ds.init(initState);
        ds.processUpdate(deltaRequest(TypeUrl.EDS, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(TypeUrl.EDS.getTypeUrl())
            .addResourceNamesSubscribe("endpoint-current")
            .putInitialResourceVersions("endpoint-unsubscribed", "1")
            .build()));

        verify(responseObserver).onNext(responseCaptor.capture());
        DeltaDiscoveryResponse resp = responseCaptor.getValue();
        assertThat(resp.getTypeUrl()).isEqualTo(TypeUrl.EDS.getTypeUrl());
        assertThat(resp.getResourcesList()).hasSize(1).first().extracting(Resource::getName).isEqualTo("endpoint-current");
        assertThat(resp.getRemovedResourcesList()).isEmpty();
        assertThat(ds.hasDeferredReconnectRemovals()).isFalse();
    }

    private CommonDiscoveryRequest<DeltaDiscoveryRequest> deltaRequest(TypeUrl typeUrl, DeltaDiscoveryRequest request) {
        return CommonDiscoveryRequest.<DeltaDiscoveryRequest>builder()
            .typeUrl(typeUrl.getTypeUrl())
            .message(request)
            .build();
    }

    private CommonDiscoveryRequest<DeltaDiscoveryRequest> ackRequest(TypeUrl typeUrl, String nonce) {
        return deltaRequest(typeUrl, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(typeUrl.getTypeUrl())
            .setResponseNonce(nonce)
            .build());
    }

    private CommonDiscoveryRequest<DeltaDiscoveryRequest> nonAckRequest(TypeUrl typeUrl) {
        return deltaRequest(typeUrl, DeltaDiscoveryRequest.newBuilder()
            .setTypeUrl(typeUrl.getTypeUrl())
            .build());
    }

    private void assertNoAdditionalResponsesSent(StreamObserver<DeltaDiscoveryResponse> responseObserver, int expectedResponsesSoFar) {
        verify(responseObserver, times(expectedResponsesSoFar)).onNext(any());
    }

    private DeltaDiscoveryResponse nextResponse(InOrder inOrder, StreamObserver<DeltaDiscoveryResponse> responseObserver) {
        inOrder.verify(responseObserver).onNext(responseCaptor.capture());
        return responseCaptor.getValue();
    }

}
