package com.transferwise.envoy.xds.api.utils;

import com.transferwise.envoy.xds.api.Mergeable;
import com.transferwise.envoy.xds.api.StateBacklog;
import com.transferwise.envoy.xds.api.StateBacklogFactory;

/**
 * A StateBacklog implementation that merges Mergable state together.
 * @param <StateUpdT> State update type
 */
public class MergingStateBacklog<StateUpdT extends Mergeable<StateUpdT>> implements StateBacklog<StateUpdT> {

    private StateUpdT backlog = null;

    private MergingStateBacklog() {
    }

    @Override
    public synchronized boolean isEmpty() {
        return backlog == null;
    }

    @Override
    public synchronized void put(StateUpdT update) {
        if (backlog == null) {
            backlog = update;
        } else {
            backlog = backlog.merge(update);
        }
    }

    @Override
    public synchronized StateUpdT take() {
        StateUpdT value = backlog;
        backlog = null;
        return value;
    }

    public static <StateUpdT extends Mergeable<StateUpdT>> MergingStateBacklogFactory<StateUpdT> factory() {
        return new MergingStateBacklogFactory<>();
    }

    public static class MergingStateBacklogFactory<StateUpdT extends Mergeable<StateUpdT>> implements StateBacklogFactory<StateUpdT> {

        @Override
        public MergingStateBacklog<StateUpdT> build() {
            return new MergingStateBacklog<>();
        }
    }
}
