package com.transferwise.envoy.xds.api.utils;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.Value;
import org.junit.jupiter.api.Test;

public class QueueingStateBacklogTest {

    @Value
    private static class SimpleUpdate {

        String name;

    }

    @Test
    public void testEmptyBacklogIsEmpty() {
        QueueingStateBacklog<SimpleUpdate> merger = QueueingStateBacklog.<SimpleUpdate>factory().build();
        assertThat(merger.isEmpty()).isTrue();
        assertThat(merger.take()).isNull();
    }

    @Test
    public void testWhatGoesInMustComeOut() {
        QueueingStateBacklog<SimpleUpdate> merger = QueueingStateBacklog.<SimpleUpdate>factory().build();
        SimpleUpdate a = new SimpleUpdate("a");
        merger.put(a);
        assertThat(merger.isEmpty()).isFalse();
        assertThat(merger.take()).isSameAs(a);
    }

    @Test
    public void testEmptiedIsEmpty() {
        QueueingStateBacklog<SimpleUpdate> merger = QueueingStateBacklog.<SimpleUpdate>factory().build();
        SimpleUpdate a = new SimpleUpdate("a");
        merger.put(a);
        merger.take();
        assertThat(merger.isEmpty()).isTrue();
        assertThat(merger.take()).isNull();
    }

    @Test
    public void testQueueIsOrdered() {
        QueueingStateBacklog<SimpleUpdate> merger = QueueingStateBacklog.<SimpleUpdate>factory().build();
        final SimpleUpdate a = new SimpleUpdate("a");
        merger.put(a);
        final SimpleUpdate b = new SimpleUpdate("b");
        merger.put(b);
        // This is a FIFO queue, it does not merge.
        assertThat(merger.isEmpty()).isFalse();
        assertThat(merger.take()).isSameAs(a);
        assertThat(merger.isEmpty()).isFalse();
        assertThat(merger.take()).isSameAs(b);
        assertThat(merger.isEmpty()).isTrue();
        assertThat(merger.take()).isNull();
    }

}
