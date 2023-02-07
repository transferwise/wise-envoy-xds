package com.transferwise.envoy.xds;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import com.transferwise.envoy.xds.api.StateBacklog;
import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Co-ordinates ordering of messages between discovery services.
 * This class is not thread safe! External synchronization must be provided.
 *
 * @param <RequestT> The discovery Request type (DeltaDiscoveryRequest or DiscoveryRequest)
 */
public class DiscoveryServiceManager<RequestT extends Message, StateUpdT> {

    private final List<DiscoveryService<RequestT, StateUpdT>> preOrder;
    private final List<DiscoveryService<RequestT, StateUpdT>> postOrder;

    private final Map<TypeUrl, DiscoveryService<RequestT, StateUpdT>> discoveryServices;

    /**
     * Iterator for obtaining the next discovery service we need to send messages from.
     */
    private Iterator<DiscoveryService<RequestT, StateUpdT>> current = null;

    /**
     * The discovery service we're waiting on envoy to ack messages from to allow the current push to continue.
     * null if we are not waiting on anything.
     */
    private DiscoveryService<RequestT, StateUpdT> waitingOn = null;

    /**
     * The set of discovery services, aside from waitingOn, that are awaiting ack messages from envoy.
     * While waitingOn tracks the current state of pushing updates, we also need to track which acks are outstanding
     * for messages sent in response to changes in Envoy subscriptions.
     */
    private final Set<DiscoveryService<RequestT, StateUpdT>> outstandingAcks = Sets.newIdentityHashSet();

    /**
     * Which order we're iterating through the discoveryServices in.
     * This is used to select which of preOrder or postOrder to use.
     */
    private DiscoveryService.SubState sendMode = DiscoveryService.SubState.COMPLETED;

    private final DiscoveryServiceManagerMetrics metrics;

    private final StateBacklog<StateUpdT> waitingStateBacklog;

    /**
     * The change set we're currently trying to send to envoy.
     * null if we're not currently waiting on acks from envoy for a change.
     */
    private StateUpdT currentChange = null;

    private boolean closed = false;
    private boolean initialized = false;

    private TypeUrl delayUpdatesUntilAckOf = null;

    /**
     * Checks that conditions hold, assuming init() was already called.
     */
    private void assertPostInitState() {
        Preconditions.checkState(!closed, "DiscoveryServiceManager is closed.");
        Preconditions.checkState(initialized, "DiscoveryServiceManager must be initialized before services can be initialized");
        Preconditions.checkState((waitingOn == null) == (currentChange == null), "Waiting on an outstanding change, and on a service to ack, can't happen without each other.");
    }

    /**
     * See class description.
     * @param discoveryServices The discovery service implementations
     * @param preOrder Specifies the order in which discovery services should be processed for "add" order
     * @param postOrder Specifies the order in which discovery services should be processed for "remove" order
     * @param waitingStateBacklog Manager for the backlog of state updates, updates will be passed to the backlog if we are not able to immediately start sending them to this client because we are already part way through a previous update.
     */
    public DiscoveryServiceManager(Map<TypeUrl, DiscoveryService<RequestT, StateUpdT>> discoveryServices, List<TypeUrl> preOrder, List<TypeUrl> postOrder, StateBacklog<StateUpdT> waitingStateBacklog,
                                   DiscoveryServiceManagerMetrics metrics) {
        this.discoveryServices = discoveryServices;
        this.waitingStateBacklog = waitingStateBacklog;
        this.preOrder = DiscoveryServiceOrderer.sort(preOrder, discoveryServices);
        this.postOrder = DiscoveryServiceOrderer.sort(postOrder, discoveryServices);
        this.metrics = metrics;
    }


    public void init(StateUpdT initialStateChange, TypeUrl delayUpdatesUntilAckOf) {
        init(initialStateChange);
        this.delayUpdatesUntilAckOf = delayUpdatesUntilAckOf;
    }

    /**
     * Populate the initial state of the manager.
     * All changes provided to pushUpdates are expected to apply sequentially from this initial state.
     */
    public void init(StateUpdT initialStateChange) {
        discoveryServices.forEach((t, s) -> s.init(initialStateChange));
        initialized = true;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        metrics.close();
    }

    /**
     * Process a message from Envoy.
     * This is likely an ack for a message we've sent, or a change to the subscription set.
     * @param value This type is a generic wrapper around the two types of DiscoveryRequest message.
     */
    public void processUpdate(CommonDiscoveryRequest<RequestT> value) {
        assertPostInitState();

        TypeUrl typeUrl = TypeUrl.of(value.getTypeUrl());
        DiscoveryService<RequestT, StateUpdT> discoveryService = discoveryServices.get(typeUrl);
        discoveryService.processUpdate(value);

        if (discoveryService.awaitingAck()) {
            if (outstandingAcks.add(discoveryService)) {
                metrics.onAwaitingAck();
            }
        } else {
            if (outstandingAcks.remove(discoveryService)) {
                metrics.onMessageAcked();
                if (typeUrl.equals(delayUpdatesUntilAckOf)) {
                    delayUpdatesUntilAckOf = null;
                }
            }
        }

        if (currentChange != null) {
            // Continue attempting to push the current change as this might have been an ack the current push was waiting for.
            continuePush();
        } else if (isPushAllowed()) {
            pushWaitingChangeIfAny();
        }
    }

    private boolean isPushAllowed() {
        return outstandingAcks.isEmpty() && delayUpdatesUntilAckOf == null;
    }

    private void beginPush() {
        Preconditions.checkState(currentChange != null, "beginPush() can only be called with a change lined up to be applied.");
        Preconditions.checkState(sendMode == DiscoveryService.SubState.COMPLETED, "beginPush() should be started from COMPLETED state");

        metrics.onPushBegin();

        sendMode = DiscoveryService.SubState.PRE;
        current = preOrder.iterator();
        discoveryServices.forEach((t, s) -> s.onNetworkUpdate(currentChange));
        DiscoveryService<RequestT, StateUpdT> service = nextOrNull();
        waitingOn = service;
        if (service != null) {
            service.sendNetworkUpdatePre();
        }
        // Continue pushing changes as sendNetworkUpdatePre() could be a NOOP, so there
        // wouldn't be an ack to trigger further pushes.
        continuePush();
    }

    private void enqueueChange(StateUpdT diff) {
        waitingStateBacklog.put(diff);
    }

    /**
     * Receive a ServiceChanges that may need to be communicated to envoy.
     * If pushing of a previous ServiceChanges is still in progress, or any unacked messages are outstanding, communicating this change will be deferred until those in-progress changes are finished and all messages have been acked.
     * If there's already a deferred change waiting, this change will be merged with the deferred one, and applied together eventually.
     *
     * @param diff the change to be applied.
     */
    public void pushUpdates(StateUpdT diff) {
        assertPostInitState();

        if (currentChange == null && isPushAllowed()) {
            // Nothing currently being applied, start applying this change immediately.
            currentChange = diff;
            beginPush();
        } else {
            enqueueChange(diff);
        }
    }

    private DiscoveryService<RequestT, StateUpdT> nextOrNull() {
        if (current.hasNext()) {
            return current.next();
        }
        return null;
    }

    /**
     * Complete the current push, can only be called if there is a current change in progress.
     */
    private void finishPush() {
        Preconditions.checkState(currentChange != null, "Cannot finish a push when there isn't one in progress");
        Preconditions.checkState(waitingOn == null, "Cannot finish a push when waiting on an ack for a previous pushed message");
        sendMode = DiscoveryService.SubState.COMPLETED;
        // Record time since we were first told about this change.
        metrics.onPushComplete();

        // All done!
        currentChange = null;

        pushWaitingChangeIfAny();
    }

    /**
     * Start pushing any waiting changes to envoy.
     * Can only be called if there is no current change in progress.
     */
    private void pushWaitingChangeIfAny() {
        Preconditions.checkState(currentChange == null, "Should not start pushing waiting change if we already have a current change to push.");
        currentChange = waitingStateBacklog.take();
        if (currentChange != null) {
            beginPush();
        }
    }

    /**
     * Attempt to continue the current push, if possible.
     * This can only be called if there is a change in progress.
     */
    private void continuePush() {
        assertPostInitState();
        Preconditions.checkState(currentChange != null, "Cannot continue pushing a change if we have not begun to push one.");

        if (waitingOn == null || waitingOn.awaitingAck()) {
            return;
        }
        DiscoveryService<RequestT, StateUpdT> nextService = nextOrNull();
        if (nextService == null) {
            if (sendMode.equals(DiscoveryService.SubState.PRE)) {
                sendMode = DiscoveryService.SubState.POST;
                current = postOrder.iterator();
                nextService = nextOrNull();
            }
        }
        waitingOn = nextService;

        if (nextService == null) {
            finishPush();
        } else {
            switch (sendMode) {
                case PRE -> nextService.sendNetworkUpdatePre();
                case POST -> nextService.sendNetworkUpdatePost();
                case COMPLETED -> { }
                default -> throw new IllegalStateException("Unhandled case should not happen.");
            }
            // Continue immediately, in case we are not waiting for acks.
            continuePush();
        }
    }


}
