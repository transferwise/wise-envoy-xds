package com.transferwise.envoy.xds.api.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.transferwise.envoy.xds.api.ClusterManagerEventListener;
import java.util.concurrent.TimeUnit;
import lombok.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class QueueingEventListenerTest {

    @Value
    private static class SimpleUpdate {
        String name;
    }

    @Test
    public void testEmptyBacklogIsEmpty(@Mock ClusterManagerEventListener<SimpleUpdate> mockListener) {
        QueueingEventListener<SimpleUpdate> merger = QueueingEventListener.createAndStart(mockListener);
        merger.close();
        merger.waitUntilFinished();

        verify(mockListener, never()).onNetworkChange(any());
        verify(mockListener, times(1)).close();
        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testWhatGoesInMustComeOut(@Mock ClusterManagerEventListener<SimpleUpdate> mockListener) {
        BarrierEventListener<SimpleUpdate> barrierEventListener = new BarrierEventListener<>(mockListener);
        QueueingEventListener<SimpleUpdate> merger = QueueingEventListener.createAndStart(barrierEventListener);
        SimpleUpdate a = new SimpleUpdate("a");
        merger.onNetworkChange(a);
        {
            barrierEventListener.await(5, TimeUnit.SECONDS); // start on change call
            barrierEventListener.awaitAndRunFree(5, TimeUnit.SECONDS); // complete on change call
            verify(mockListener, times(1)).onNetworkChange(a);
            verifyNoMoreInteractions(mockListener);
        }

        merger.close();
        merger.waitUntilFinished();
        {
            verify(mockListener, times(1)).close();
            verifyNoMoreInteractions(mockListener);
        }
    }


    @Test
    public void testQueueIsOrdered(@Mock ClusterManagerEventListener<SimpleUpdate> mockListener) {
        BarrierEventListener<SimpleUpdate> barrierEventListener = new BarrierEventListener<>(mockListener);
        QueueingEventListener<SimpleUpdate> merger = QueueingEventListener.createAndStart(barrierEventListener);

        final SimpleUpdate a = new SimpleUpdate("a");
        final SimpleUpdate b = new SimpleUpdate("b");
        final SimpleUpdate c = new SimpleUpdate("c");

        merger.onNetworkChange(a);
        barrierEventListener.await(5, TimeUnit.SECONDS); // Wait until the change event for a is being processed
        merger.onNetworkChange(b);
        merger.onNetworkChange(c);
        barrierEventListener.await(5, TimeUnit.SECONDS); // Finish processing a
        barrierEventListener.await(5, TimeUnit.SECONDS); // Start processing b
        barrierEventListener.await(5, TimeUnit.SECONDS); // Finish processing b
        barrierEventListener.await(5, TimeUnit.SECONDS); // Start processing c
        barrierEventListener.awaitAndRunFree(5, TimeUnit.SECONDS); // Finish processing c, let it run free and check our result.

        InOrder inOrder = inOrder(mockListener);
        inOrder.verify(mockListener).onNetworkChange(a);
        inOrder.verify(mockListener).onNetworkChange(b);
        inOrder.verify(mockListener).onNetworkChange(c);
        verifyNoMoreInteractions(mockListener);

        merger.close();
        merger.waitUntilFinished();

        verify(mockListener, times(1)).close();
        verifyNoMoreInteractions(mockListener);
    }

}
