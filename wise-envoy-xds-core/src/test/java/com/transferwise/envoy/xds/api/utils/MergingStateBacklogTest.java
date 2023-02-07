package com.transferwise.envoy.xds.api.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.transferwise.envoy.xds.api.Mergeable;
import lombok.Value;
import org.junit.jupiter.api.Test;

public class MergingStateBacklogTest {

    @Value
    private static class SimpleMergeable implements Mergeable<SimpleMergeable> {
        String name;

        @Override
        public SimpleMergeable merge(SimpleMergeable update) {
            return new SimpleMergeable(name + update.getName());
        }
    }

    @Test
    public void testEmptyBacklogIsEmpty() {
        MergingStateBacklog<SimpleMergeable> merger = MergingStateBacklog.<SimpleMergeable>factory().build();
        assertThat(merger.isEmpty()).isTrue();
        assertThat(merger.take()).isNull();
    }

    @Test
    public void testWhatGoesInMustComeOut() {
        MergingStateBacklog<SimpleMergeable> merger = MergingStateBacklog.<SimpleMergeable>factory().build();
        SimpleMergeable a = new SimpleMergeable("a");
        merger.put(a);
        assertThat(merger.isEmpty()).isFalse();
        assertThat(merger.take()).isSameAs(a);
    }

    @Test
    public void testEmptiedIsEmpty() {
        MergingStateBacklog<SimpleMergeable> merger = MergingStateBacklog.<SimpleMergeable>factory().build();
        SimpleMergeable a = new SimpleMergeable("a");
        merger.put(a);
        merger.take();
        assertThat(merger.isEmpty()).isTrue();
        assertThat(merger.take()).isNull();
    }

    @Test
    public void testMergeIsOrdered() {
        MergingStateBacklog<SimpleMergeable> merger = MergingStateBacklog.<SimpleMergeable>factory().build();
        SimpleMergeable a = new SimpleMergeable("a");
        merger.put(a);
        SimpleMergeable b = new SimpleMergeable("b");
        merger.put(b);
        // a then b...
        assertThat(merger.isEmpty()).isFalse();
        assertThat(merger.take()).isEqualTo(new SimpleMergeable("ab"));
        assertThat(merger.isEmpty()).isTrue();
        assertThat(merger.take()).isNull();
    }

}
