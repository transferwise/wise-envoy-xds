package com.transferwise.envoy.xds;

import com.google.common.base.Preconditions;
import com.google.protobuf.Message;
import com.transferwise.envoy.xds.CommonDiscoveryRequest;
import com.transferwise.envoy.xds.DiscoveryService;
import com.transferwise.envoy.xds.DiscoveryServiceManager;
import com.transferwise.envoy.xds.TypeUrl;
import com.transferwise.envoy.xds.api.StateBacklog;
import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@SuppressFBWarnings(value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"}, justification = "Mockito rule")
@ExtendWith(MockitoExtension.class)
public class DiscoveryServiceManagerTest {

    public static class DummyUpdate {

    }

    public static class QueueBacklog implements StateBacklog<DummyUpdate> {

        private final Queue<DummyUpdate> queue = new ArrayDeque<>();

        @Override
        public boolean isEmpty() {
            return queue.isEmpty();
        }

        @Override
        public void put(DummyUpdate update) {
            queue.add(update);
        }

        @Override
        public DummyUpdate take() {
            return queue.poll();
        }
    }

    @Test
    public void testSingleServicePush(@Mock DiscoveryService<Message, DummyUpdate> mockDiscoveryService) {

        DiscoveryServiceManager<Message, DummyUpdate> dsm = new DiscoveryServiceManager<>(Map.of(TypeUrl.EDS, mockDiscoveryService), List.of(TypeUrl.EDS), List.of(TypeUrl.EDS), new QueueBacklog(), DiscoveryServiceManagerMetrics.NOOP_METRICS);

        var initUpdate = new DummyUpdate();

        dsm.init(initUpdate);

        InOrder inOrder = Mockito.inOrder(mockDiscoveryService);
        inOrder.verify(mockDiscoveryService).init(initUpdate);

        var update = new DummyUpdate();

        dsm.pushUpdates(update);
        inOrder.verify(mockDiscoveryService).onNetworkUpdate(update);
        inOrder.verify(mockDiscoveryService).sendNetworkUpdatePre();
        inOrder.verify(mockDiscoveryService).awaitingAck();
        inOrder.verify(mockDiscoveryService).sendNetworkUpdatePost();
        inOrder.verify(mockDiscoveryService).awaitingAck();
        verifyNoMoreInteractions(mockDiscoveryService);
    }

    @Test
    public void testSingleServicePushBlocksUntilResponse() {
        DiscoveryService<Message, DummyUpdate> mockDiscoveryService = spy(StateAwareFakeDiscoveryService.class);

        DiscoveryServiceManager<Message, DummyUpdate> dsm = new DiscoveryServiceManager<>(Map.of(TypeUrl.EDS, mockDiscoveryService), List.of(TypeUrl.EDS), List.of(TypeUrl.EDS), new QueueBacklog(), DiscoveryServiceManagerMetrics.NOOP_METRICS);

        dsm.init(new DummyUpdate());
        final var update = new DummyUpdate();
        final var update2 = new DummyUpdate();
        final var update3 = new DummyUpdate();
        final var request = CommonDiscoveryRequest.builder()
            .typeUrl(TypeUrl.EDS.getTypeUrl())
            .build();

        dsm.pushUpdates(update);

        InOrder inOrder = Mockito.inOrder(mockDiscoveryService);
        inOrder.verify(mockDiscoveryService).onNetworkUpdate(update);
        inOrder.verify(mockDiscoveryService).sendNetworkUpdatePre();
        inOrder.verify(mockDiscoveryService, never()).onNetworkUpdate(update2);

        // blocked on ack
        dsm.pushUpdates(update2); // This should have no effect as it'll be queued.
        dsm.pushUpdates(update3); // This should have no effect as it'll be queued.
        dsm.processUpdate(request);

        inOrder.verify(mockDiscoveryService).processUpdate(request);
        inOrder.verify(mockDiscoveryService).sendNetworkUpdatePost();

        // blocked on ack again
        dsm.processUpdate(request);
        inOrder.verify(mockDiscoveryService).onNetworkUpdate(update2);
        inOrder.verify(mockDiscoveryService).sendNetworkUpdatePre();

        // blocked on ack yet again
        dsm.processUpdate(request);

        inOrder.verify(mockDiscoveryService).sendNetworkUpdatePost();

        // blocked on ack again
        dsm.processUpdate(request);
        inOrder.verify(mockDiscoveryService).onNetworkUpdate(update3);
        inOrder.verify(mockDiscoveryService).sendNetworkUpdatePre();

        // blocked on ack yet again
        dsm.processUpdate(request);

        inOrder.verify(mockDiscoveryService).sendNetworkUpdatePost();
    }

    @Test
    public void testSingleUpdateMultipleDiscoveryServices() {
        final DiscoveryService<Message, DummyUpdate> mockDiscoveryServiceA = spy(StateAwareFakeDiscoveryService.class);
        final DiscoveryService<Message, DummyUpdate> mockDiscoveryServiceB = spy(StateAwareFakeDiscoveryService.class);

        DiscoveryServiceManager<Message, DummyUpdate> dsm = new DiscoveryServiceManager<>(
            Map.of(TypeUrl.EDS, mockDiscoveryServiceA, TypeUrl.CDS, mockDiscoveryServiceB),
            List.of(TypeUrl.EDS, TypeUrl.CDS), List.of(TypeUrl.CDS, TypeUrl.EDS),
            new QueueBacklog(), DiscoveryServiceManagerMetrics.NOOP_METRICS
        );
        dsm.init(new DummyUpdate());
        final var update = new DummyUpdate();

        final var requestA = CommonDiscoveryRequest.builder()
                .typeUrl(TypeUrl.EDS.getTypeUrl())
                .build();
        final var requestB = CommonDiscoveryRequest.builder()
                .typeUrl(TypeUrl.CDS.getTypeUrl())
                .build();


        dsm.pushUpdates(update);


        verify(mockDiscoveryServiceB).onNetworkUpdate(update);
        verify(mockDiscoveryServiceA).onNetworkUpdate(update);

        InOrder inOrder = Mockito.inOrder(mockDiscoveryServiceA, mockDiscoveryServiceB);
        inOrder.verify(mockDiscoveryServiceA).sendNetworkUpdatePre();
        inOrder.verify(mockDiscoveryServiceB, never()).sendNetworkUpdatePre(); // Shouldn't continue until acked.

        // blocks on ack
        dsm.processUpdate(requestA);

        inOrder.verify(mockDiscoveryServiceA).processUpdate(requestA);
        inOrder.verify(mockDiscoveryServiceB).sendNetworkUpdatePre();
        inOrder.verify(mockDiscoveryServiceB, never()).sendNetworkUpdatePost(); // Shouldn't continue until acked.
        // blocks on ack
        dsm.processUpdate(requestB);

        inOrder.verify(mockDiscoveryServiceB).processUpdate(requestB);
        inOrder.verify(mockDiscoveryServiceB).sendNetworkUpdatePost();
        inOrder.verify(mockDiscoveryServiceA, never()).sendNetworkUpdatePost(); // Shouldn't continue until acked.
        // blocks on ack
        dsm.processUpdate(requestB);

        inOrder.verify(mockDiscoveryServiceB).processUpdate(requestB);
        inOrder.verify(mockDiscoveryServiceA).sendNetworkUpdatePost();
        // blocks on ack

        dsm.processUpdate(requestA);
        inOrder.verify(mockDiscoveryServiceA).processUpdate(requestA);
    }

    @Test
    public void testUpdateDuringInitDoesNotPush() {
        // To reduce the risk of running into bugs in envoy's cluster initialisation code we don't actively push updates on any discovery service while an existing response
        // for any of them remains unacked.
        // This doesn't prevent us answering interleaved requests from envoy, but it does block pushing network updates.
        // TODO(jono): consider removing this restriction? Using delayUpdatesUntilAckOf can be used to more reliably work around such bugs...

        final DiscoveryService<Message, DummyUpdate> mockDiscoveryServiceA = spy(StateAwareFakeDiscoveryService.class);
        final DiscoveryService<Message, DummyUpdate> mockDiscoveryServiceB = spy(StateAwareFakeDiscoveryService.class);

        DiscoveryServiceManager<Message, DummyUpdate> dsm = new DiscoveryServiceManager<>(
            Map.of(TypeUrl.CDS, mockDiscoveryServiceA, TypeUrl.EDS, mockDiscoveryServiceB),
            List.of(TypeUrl.CDS, TypeUrl.EDS), List.of(TypeUrl.EDS, TypeUrl.CDS),
            new QueueBacklog(), DiscoveryServiceManagerMetrics.NOOP_METRICS
        );

        dsm.init(new DummyUpdate());

        final var update = new DummyUpdate();

        // The discovery service type isn't really relevant here, this should work with any types. I chose CDS and EDS simply because they really do interleave this way.
        // The requests are empty, the fake discovery service doesn't care about their contents, they're sufficient to cause a state transition.
        final var cdsRequest = CommonDiscoveryRequest.builder()
            .typeUrl(TypeUrl.CDS.getTypeUrl())
            .build();
        final var edsRequest = CommonDiscoveryRequest.builder()
            .typeUrl(TypeUrl.EDS.getTypeUrl())
            .build();

        dsm.processUpdate(cdsRequest);
        dsm.processUpdate(edsRequest);
        dsm.processUpdate(edsRequest); // Acked EDS
        dsm.pushUpdates(update); // Should do nothing until CDS is acked.

        verify(mockDiscoveryServiceB).init(any());
        verify(mockDiscoveryServiceA).init(any());

        InOrder inOrder = Mockito.inOrder(mockDiscoveryServiceA, mockDiscoveryServiceB);
        inOrder.verify(mockDiscoveryServiceA).processUpdate(cdsRequest);
        inOrder.verify(mockDiscoveryServiceB).processUpdate(edsRequest);
        inOrder.verify(mockDiscoveryServiceB).processUpdate(edsRequest);
        inOrder.verify(mockDiscoveryServiceA, never()).sendNetworkUpdatePre();
        inOrder.verify(mockDiscoveryServiceB, never()).sendNetworkUpdatePre();
        dsm.processUpdate(cdsRequest); // Acked EDS
        inOrder.verify(mockDiscoveryServiceA).processUpdate(cdsRequest);
        inOrder.verify(mockDiscoveryServiceA).sendNetworkUpdatePre();
    }

    @Test
    public void testUpdatesDelayedUntilConfiguredAck() {
        // We optionally allow the DSM to block updates until the first ACK of a given DS. This can be useful to work around bugs in envoy's cluster initialisation.
        // For example to work around https://github.com/envoyproxy/envoy/issues/16035 in older envoy versions, our service mesh blocks updates until RDS is ACKed
        // since envoy won't ACK that until after clusters have been fully initialized.

        final DiscoveryService<Message, DummyUpdate> mockDiscoveryServiceA = spy(StateAwareFakeDiscoveryService.class);
        final DiscoveryService<Message, DummyUpdate> mockDiscoveryServiceB = spy(StateAwareFakeDiscoveryService.class);

        DiscoveryServiceManager<Message, DummyUpdate> dsm = new DiscoveryServiceManager<>(
            Map.of(TypeUrl.CDS, mockDiscoveryServiceA, TypeUrl.RDS, mockDiscoveryServiceB),
            List.of(TypeUrl.CDS, TypeUrl.RDS), List.of(TypeUrl.RDS, TypeUrl.CDS),
            new QueueBacklog(), DiscoveryServiceManagerMetrics.NOOP_METRICS
        );

        dsm.init(new DummyUpdate(), TypeUrl.RDS); // Note configuring delayUpdatesUntilAckOf

        final var update = new DummyUpdate();

        // The discovery service type isn't really relevant here, this should work with any types.
        // The requests are empty, the fake discovery service doesn't care about their contents, they're sufficient to cause a state transition.
        final var cdsRequest = CommonDiscoveryRequest.builder()
            .typeUrl(TypeUrl.CDS.getTypeUrl())
            .build();
        final var rdsRequest = CommonDiscoveryRequest.builder()
            .typeUrl(TypeUrl.RDS.getTypeUrl())
            .build();

        final InOrder inOrder = Mockito.inOrder(mockDiscoveryServiceA, mockDiscoveryServiceB);
        dsm.processUpdate(cdsRequest);
        dsm.processUpdate(cdsRequest); // Ack CDS

        verify(mockDiscoveryServiceA).init(any());
        verify(mockDiscoveryServiceB).init(any());

        inOrder.verify(mockDiscoveryServiceA).processUpdate(cdsRequest);
        inOrder.verify(mockDiscoveryServiceA).processUpdate(cdsRequest);

        dsm.pushUpdates(update); // Should do nothing until RDS is acked.

        inOrder.verify(mockDiscoveryServiceA, never()).sendNetworkUpdatePre();
        inOrder.verify(mockDiscoveryServiceB, never()).sendNetworkUpdatePre();
        dsm.processUpdate(rdsRequest); // asked for RDS
        inOrder.verify(mockDiscoveryServiceB).processUpdate(rdsRequest);
        inOrder.verify(mockDiscoveryServiceA, never()).sendNetworkUpdatePre();
        inOrder.verify(mockDiscoveryServiceB, never()).sendNetworkUpdatePre();
        dsm.processUpdate(rdsRequest); // Acked RDS
        inOrder.verify(mockDiscoveryServiceB).processUpdate(rdsRequest);
        inOrder.verify(mockDiscoveryServiceA).sendNetworkUpdatePre();
        dsm.processUpdate(cdsRequest); // ACK CDS
        inOrder.verify(mockDiscoveryServiceB).sendNetworkUpdatePre();
        dsm.processUpdate(rdsRequest); // ACK RDS
        inOrder.verify(mockDiscoveryServiceB).sendNetworkUpdatePost();
        dsm.processUpdate(rdsRequest); // ACK RDS
        inOrder.verify(mockDiscoveryServiceA).sendNetworkUpdatePost();
    }

    public static class StateAwareFakeDiscoveryService implements DiscoveryService<Message, DummyUpdate> {

        private boolean initialized = false;
        private boolean awaitingAck = false;

        public StateAwareFakeDiscoveryService() {
        }

        @Override
        public void processUpdate(CommonDiscoveryRequest<Message> value) {
            Preconditions.checkState(initialized);
            // If we're waiting, assume this was an ack
            // If we're not waiting, assume it was a request to subscribe to new stuff.
            awaitingAck = !awaitingAck;
        }

        @Override
        public boolean awaitingAck() {
            return awaitingAck;
        }

        @Override
        public void init(DummyUpdate state) {
            Preconditions.checkState(!initialized);
            initialized = true;
        }

        @Override
        public void onNetworkUpdate(DummyUpdate changes) {
            Preconditions.checkState(initialized);
        }

        @Override
        public void sendNetworkUpdatePre() {
            Preconditions.checkState(initialized);
            // Assume we sent something
            awaitingAck = true;
        }

        @Override
        public void sendNetworkUpdatePost() {
            Preconditions.checkState(initialized);
            awaitingAck = true;
        }

        @Override
        public TypeUrl getTypeUrl() {
            throw new UnsupportedOperationException("Not implemented");
        }
    }

}
