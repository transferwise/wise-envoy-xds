package com.transferwise.envoy.xds.api.utils;

import com.transferwise.envoy.xds.api.ClusterManagerEventListener;
import java.util.concurrent.BlockingQueue;
import lombok.extern.slf4j.Slf4j;

/**
 * An event listener that accepts network changes, and queues them to be executed asynchronously against a delegate.
 * Where possible you should use the QueueingMergingEventListener instead, as it will avoid update latency during periods of high network churn.
 * This implementation is useful for testing where deterministic behaviour is important (the merging version behaviour will depend on the speed of envoy applying updates!)
 * It will attempt to drain the queue fully after close() is called.
 * @param <StateUpdT> Update type
 */
@Slf4j
public class QueueingEventListener<StateUpdT> extends ThreadedQueuingEventListener<StateUpdT> {

    public QueueingEventListener(ClusterManagerEventListener<StateUpdT> delegate) {
        super(new FifoStrategy<>(), delegate);
    }

    private static class FifoStrategy<StateUpdT> implements QueueTakeStrategy<StateUpdT> {
        @Override
        public StateUpdT take(BlockingQueue<StateUpdT> queue) throws InterruptedException {
            return queue.take();
        }
    }

    public static <StateUpdT> QueueingEventListener<StateUpdT> createAndStart(ClusterManagerEventListener<StateUpdT> delegate) {
        QueueingEventListener<StateUpdT> runner = new QueueingEventListener<>(delegate);
        runner.start();
        return runner;
    }

}
