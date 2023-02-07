package com.transferwise.envoy.xds.api.utils;

import com.transferwise.envoy.xds.api.StateBacklog;
import com.transferwise.envoy.xds.api.StateBacklogFactory;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A StateBacklog implementation that queues state in FIFO order.
 * Where possible you should use the MergingStateBacklog instead, as it will avoid update latency during periods of high network churn.
 * This backlog is useful for testing where deterministic behaviour is important (the merging version behaviour will depend on the speed of envoy applying updates!)
 * @param <StateUpdT> State update type
 */
public class QueueingStateBacklog<StateUpdT> implements StateBacklog<StateUpdT> {

    private final Queue<StateUpdT> backlog = new ConcurrentLinkedQueue<>();

    private QueueingStateBacklog() {
    }

    @Override
    public boolean isEmpty() {
        return backlog.isEmpty();
    }

    @Override
    public void put(StateUpdT update) {
        backlog.add(update);
    }

    @Override
    public StateUpdT take() {
        return backlog.poll();
    }

    public static <StateUpdT> QueueingStateBacklogFactory<StateUpdT> factory() {
        return new QueueingStateBacklogFactory<>();
    }

    public static class QueueingStateBacklogFactory<StateUpdT> implements StateBacklogFactory<StateUpdT> {

        @Override
        public QueueingStateBacklog<StateUpdT> build() {
            return new QueueingStateBacklog<>();
        }
    }
}
