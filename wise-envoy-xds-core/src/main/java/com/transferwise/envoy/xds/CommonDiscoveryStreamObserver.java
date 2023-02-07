package com.transferwise.envoy.xds;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.protobuf.Message;
import com.transferwise.envoy.xds.api.ClientConfigProvider;
import com.transferwise.envoy.xds.api.ClientHandle;
import com.transferwise.envoy.xds.api.ClusterEventSource;
import com.transferwise.envoy.xds.api.ClusterManagerEventListener;
import com.transferwise.envoy.xds.api.XdsEventListener;
import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.util.function.Function;

@Slf4j
public class CommonDiscoveryStreamObserver<T extends Message, R extends Message, StateUpdT, DetailsT>
        implements StreamObserver<T>, ClusterManagerEventListener<StateUpdT>, ClientHandle {

    private boolean isDead = false;

    private final StreamObserver<R> responseObserver;
    private final ClusterEventSource<StateUpdT> clusterManager;
    private NodeConfig<DetailsT> xdsConfig = null;

    private final DiscoveryServiceManagerFactory<T, R, StateUpdT, DetailsT> discoveryServiceManagerFactory;
    private DiscoveryServiceManager<T, StateUpdT> discoveryServiceManager = null;

    private final Function<T, CommonDiscoveryRequest<T>> commonDiscoveryRequestConverter;

    //private final ServerConfig serverConfig;
    private final DiscoveryServiceManagerMetrics metrics;

    private final ImmutableList<XdsEventListener<DetailsT>> listeners;

    private final ClientConfigProvider<DetailsT> configProvider;

    /**
     * If set then we will delay sending mesh updates to the client until we have received the first ACK for this TypeUrl.
     * This allows us to prevent clients being sent endpoint updates, which interfere with the envoy init process, until
     * they have acked a later DS (for example RDS or LDS, which are only ACKed by envoy after clusters have been fully
     * initialised.)
     * If null then we don't delay the updates.
     * Implementation detail: this will be set to null once the expected ack is received.
     */
    private TypeUrl delayUpdatesUntilAckOf = null;

    private String nodeId = null;
    private String clusterId = null;

    private Node node = null;

    public CommonDiscoveryStreamObserver(
        StreamObserver<R> responseObserver,
        ClusterEventSource<StateUpdT> clusterManager,
        DiscoveryServiceManagerFactory<T, R, StateUpdT, DetailsT> discoveryServiceManagerFactory,
        Function<T, CommonDiscoveryRequest<T>> commonDiscoveryRequestConverter,
        ClientConfigProvider<DetailsT> configProvider,
        ImmutableList<XdsEventListener<DetailsT>> listeners,
        DiscoveryServiceManagerMetrics metrics) {
        this.responseObserver = responseObserver;
        this.clusterManager = clusterManager;
        this.discoveryServiceManagerFactory = discoveryServiceManagerFactory;
        this.commonDiscoveryRequestConverter = commonDiscoveryRequestConverter;
        this.metrics = metrics;
        this.listeners = listeners;
        this.configProvider = configProvider;
    }

    @GuardedBy("this")
    private void extractNodeData(Node node) {
        if (node == null) {
            log.warn("Client fed us null node data, envoy bug?");
            throw new StatusRuntimeException(Status.INVALID_ARGUMENT);
        }
        XdsConfig<DetailsT> config = configProvider.lookup(node);

        this.node = node;
        xdsConfig = NodeConfig.forNode(node, config);
        notifyClientConnected();

        delayUpdatesUntilAckOf = xdsConfig.getXdsConfig().getDelayUpdatesUntilAckOf();
        nodeId = node.getId();
        clusterId = node.getCluster();
    }

    @Override
    public synchronized void onNext(T concreteValue) {
        try {
            Preconditions.checkState(!isDead);
            CommonDiscoveryRequest<T> value = commonDiscoveryRequestConverter.apply(concreteValue);

            if (value.getTypeUrl() == null) {
                throw new RuntimeException("Missing type URL on request");
            }

            if (node == null) {
                log.debug("New envoy connected: {}", value.getNode() != null ? value.getNode().getId() : null);
                extractNodeData(value.getNode());
            }

            if (value.getErrorDetail() != null) {
                if (xdsConfig.getXdsConfig().isSilentNacks() && value.getErrorDetail().getCode() == Status.Code.INTERNAL.value()) {
                    log.info("Client {} reports error: {}", nodeId, value.getErrorDetail());
                } else {
                    log.error("Client {} reports error: {}", nodeId, value.getErrorDetail());
                }
            }

            TypeUrl typeUrl = TypeUrl.of(value.getTypeUrl());
            if (typeUrl == null) {
                throw new RuntimeException("Client " + nodeId + " in cluster " + clusterId + " asked for unknown type URL " + value.getTypeUrl());
            }
            log.debug("DiscoveryRequest T={}", value.getTypeUrl());
            if (discoveryServiceManager == null) {
                discoveryServiceManager = discoveryServiceManagerFactory.build(responseObserver, xdsConfig, metrics);
                discoveryServiceManager.init(clusterManager.subscribe(this), delayUpdatesUntilAckOf);
            }

            try {
                discoveryServiceManager.processUpdate(value);
            } catch (ClientNackException nack) {
                if (xdsConfig.getXdsConfig().isSilentNacks()) {
                    log.info("Client rejected update", nack);
                    // Just ignore the response. It'll make them hang around, and not go into a tight retry loop.
                    return;
                }
                throw nack;
            }
        } catch (Throwable t) {
            this.onError(t); // Despite docs on interface, upstream is not calling onError :-(
            throw t;
        }
    }

    private static class RunWithExceptions implements Closeable {

        private Throwable caught = null;

        private final String doingWhat;

        public RunWithExceptions(String doingWhat) {
            this.doingWhat = doingWhat;
        }

        private void exec(Runnable thing) {
            try {
                thing.run();
            } catch (Error e) {
                if (caught == null) {
                    caught = e;
                } else if (caught instanceof Error) {
                    // If we'd already caught an Error, then throw that one and suppress this one.
                    caught.addSuppressed(e);
                } else {
                    // We want to make sure Error gets thrown rather than whatever Exceptions we'd captured.
                    e.addSuppressed(caught);
                    caught = e;
                }
            } catch (Throwable e) {
                // The first Throwable we caught will be rethrown, all others will be suppressed unless they are Error (see catch block above.)
                if (caught == null) {
                    caught = e;
                }
                caught.addSuppressed(e);
            }
        }

        @Override
        public void close() {
            if (caught != null) {
                // Directly rethrow if we can, but we'll have to wrap checked Exceptions in a RuntimeException.
                if (caught instanceof Error rethrow) {
                    throw rethrow;
                }
                if (caught instanceof RuntimeException rethrow) {
                    throw rethrow;
                }
                throw new RuntimeException("Caught exception while " + doingWhat, caught);
            }
        }
    }

    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "Spotbugs is confused by lambdas")
    @GuardedBy("this")
    private void notifyClientDisconnected() {
        if (node == null) {
            return;
        }
        try (RunWithExceptions runner = new RunWithExceptions("notifying XdsEventListeners of client disconnection")) {
            for (XdsEventListener<DetailsT> listener: listeners) {
                runner.exec(() -> listener.onClientDisconnected(this, node));
            }
        }
    }

    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "Spotbugs is confused by lambdas")
    @GuardedBy("this")
    private void notifyClientConnected() {
        if (node == null) {
            return;
        }
        try (RunWithExceptions runner = new RunWithExceptions("notifying XdsEventListeners of client connection")) {
            for (XdsEventListener<DetailsT> listener: listeners) {
                runner.exec(() -> listener.onNewClient(this, node, xdsConfig.getXdsConfig()));
            }
        }
    }

    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "Spotbugs is confused by lambdas")
    @GuardedBy("this")
    private void cleanupOnDisconnect(RunWithExceptions runner) {
        isDead = true;
        if (discoveryServiceManager != null) {
            runner.exec(() -> clusterManager.unsubscribe(this));
            runner.exec(() -> discoveryServiceManager.close());
            discoveryServiceManager = null;
        }
        runner.exec(this::notifyClientDisconnected);
    }

    @Override
    public synchronized void onError(Throwable t) {
        if (isDead) {
            log.warn("onError called on already dead CDSO. Maybe upstream fixed the bug where onError wasn't getting called?");
            return;
        }
        if (!(t instanceof StatusRuntimeException r) || Status.Code.CANCELLED.equals(r.getStatus().getCode())) {
            log.warn("Error streaming to an ADS client", t);
        }

        try (RunWithExceptions runner = new RunWithExceptions("handling stream error")) {
            cleanupOnDisconnect(runner);
            runner.exec(() -> responseObserver.onError(t));
        }
    }

    @Override
    public synchronized void onCompleted() {
        if (isDead) {
            log.warn("onCompleted called on already dead CDSO.");
            return;
        }
        log.debug("Completed: " + this.node);

        try (RunWithExceptions runner = new RunWithExceptions("handling stream completed")) {
            cleanupOnDisconnect(runner);
            runner.exec(responseObserver::onCompleted);
        }
    }

    @Override
    public synchronized void onNetworkChange(StateUpdT diff) {
        if (isDead) {
            return;
        }

        try {
            discoveryServiceManager.pushUpdates(diff);
        } catch (Throwable t) {
            this.onError(t);
            throw t;
        }
    }
}
