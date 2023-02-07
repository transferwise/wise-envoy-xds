package com.transferwise.envoy.xds.api;

/**
 * Factory for backlog instances.
 * This is used to create a new backlog for each client.
 * @param <StateUpdT> state update type
 */
public interface StateBacklogFactory<StateUpdT> {

    /**
     * Build a state backlog instance.
     * @return a new instance of a state backlog
     */
    StateBacklog<StateUpdT> build();

}
