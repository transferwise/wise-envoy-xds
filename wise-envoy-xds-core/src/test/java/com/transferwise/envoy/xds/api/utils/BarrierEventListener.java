package com.transferwise.envoy.xds.api.utils;

import com.transferwise.envoy.xds.api.ClusterManagerEventListener;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class BarrierEventListener<T> implements ClusterManagerEventListener<T> {

    private final ClusterManagerEventListener<T> delegate;
    private final AtomicBoolean runFree = new AtomicBoolean(false);

    private final AtomicReference<CyclicBarrier> barrier = new AtomicReference<>(new CyclicBarrier(2, () -> {
        if (runFree.get()) {
            clearBarrier();
        }
    }));

    BarrierEventListener(ClusterManagerEventListener<T> delegate) {
        this.delegate = delegate;
    }

    private void clearBarrier() {
        barrier.set(null);
    }

    @Override
    public void onNetworkChange(T diff) {
        await();
        delegate.onNetworkChange(diff);
        await();
    }

    @Override
    public void close() {
        delegate.close();
    }

    public void await() {
        doAwait(false, 0L, null);
    }

    public void await(long timeout, TimeUnit unit) {
        doAwait(true, timeout, unit);
    }

    private void doAwait(boolean timed, long timeout, TimeUnit unit) {
        try {
            CyclicBarrier barrier = this.barrier.get();
            if (barrier == null) {
                return;
            }
            if (timed) {
                barrier.await(timeout, unit);
            } else {
                barrier.await();
            }
        } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public void awaitAndRunFree(long timeout, TimeUnit unit) {
        runFree.set(true); // Won't actually take effect until next barrier trip
        await(timeout, unit);
    }
}
