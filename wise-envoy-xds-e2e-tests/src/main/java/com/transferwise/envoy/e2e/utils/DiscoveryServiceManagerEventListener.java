package com.transferwise.envoy.e2e.utils;

import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class DiscoveryServiceManagerEventListener implements DiscoveryServiceManagerMetrics {

    private final AtomicBoolean pushInProgress = new AtomicBoolean(false);
    private final AtomicLong pushed = new AtomicLong(0);
    private final AtomicLong messagesOutstanding = new AtomicLong(0);

    @Override
    public void close() {
        onPushComplete();
    }

    @Override
    public void onPushBegin() {
        if (pushInProgress.getAndSet(true)) {
            throw new IllegalStateException("Multiple pushes in progress");
        }
    }

    @Override
    public void onPushComplete() {
        if (pushInProgress.getAndSet(false)) {
            pushed.incrementAndGet();
        }
    }

    @Override
    public void onAwaitingAck() {
        messagesOutstanding.incrementAndGet();
    }

    @Override
    public void onMessageAcked() {
        messagesOutstanding.decrementAndGet();
    }

    public boolean isPushInProgress() {
        return pushInProgress.get();
    }

    public boolean awaitingAcks() {
        return messagesOutstanding.get() != 0;
    }

    public boolean isIdle() {
        return !isPushInProgress() && !awaitingAcks();
    }

    public long getPushed() {
        return pushed.get();
    }
}
