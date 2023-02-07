package com.transferwise.envoy.xds.api;

/**
 * Track states waiting to be applied.
 * The simplest possible implementation would be a FIFO queue.
 * But ideally your states should be mergeable, and you should merge the pending ones together instead.
 * It is not guaranteed that all state updates will go through the backlog! We will only put to the backlog if an event arrives while we are still sending resources for a previous event. If no event is currently being applied we will skip calling
 * the backlog entirely.
 * @param <StateUpdT> state update type
 */
public interface StateBacklog<StateUpdT> {

    /**
     * Is the backlog currently empty.
     * @return true if there are no pending updates
     */
    boolean isEmpty();

    /**
     * Put something into the backlog.
     * This does not necessarily store the update being put, it might discard it, or perform some transformation.
     * While there is something in the backlog calls to isEmpty should return false.
     * @param update state update to store
     */
    void put(StateUpdT update);

    /**
     * Take the next change to apply out of the backlog, or null if the backlog is empty.
     * This might not be identical to an object that was put() into the backlog.
     * If this was the last change remaining in the backlog, subsequent calls to isEmpty will return true.
     * @return The next change to apply.
     */
    StateUpdT take();

}
