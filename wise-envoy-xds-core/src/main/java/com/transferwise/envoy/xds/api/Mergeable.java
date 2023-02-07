package com.transferwise.envoy.xds.api;

public interface Mergeable<StateUpdT extends Mergeable<StateUpdT>> {

    /**
     * Merge an update into this instance.
     * The result of the merge operation should be a state update equivalent to applying this instance followed by the supplied update.
     * @param update updated state
     * @return a StateUpdT representing the result of applying this instance and then update
     */
    StateUpdT merge(StateUpdT update);

}
