package com.transferwise.envoy.xds.delta;

import com.google.common.collect.ImmutableSet;
import com.transferwise.envoy.xds.delta.SubListSubManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("Duplicates")
@SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "Mockito rule")
public class SubListSubManagerSpec {

    @Test
    public void testNotSubscribed() {
        SubListSubManager subManager = new SubListSubManager(null);

        assertThat(subManager.isSubscribedTo("foo")).isFalse();
    }

    @Test
    public void testSubscribe() {
        SubListSubManager subManager = new SubListSubManager(null);

        Predicate<String> filter = subManager.processResourceListChange(ImmutableSet.of("foo", "bar"), ImmutableSet.of()).orElseThrow();

        assertThat(filter.test("foo")).isTrue();
        assertThat(filter.test("bar")).isTrue();
        assertThat(filter.test("baz")).isFalse();
        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isFalse();
    }


    @Test
    public void testSubscribeMore() {
        SubListSubManager subManager = new SubListSubManager(null);

        Predicate<String> filter = subManager.processResourceListChange(ImmutableSet.of("foo"), ImmutableSet.of()).orElseThrow();

        assertThat(filter.test("foo")).isTrue();
        assertThat(filter.test("bar")).isFalse();
        assertThat(filter.test("baz")).isFalse();

        filter = subManager.processResourceListChange(ImmutableSet.of("bar"), ImmutableSet.of()).orElseThrow();

        assertThat(filter.test("foo")).isFalse();
        assertThat(filter.test("bar")).isTrue();
        assertThat(filter.test("baz")).isFalse();

        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isFalse();
    }

    @Test
    public void testSubscribeTwice() {
        SubListSubManager subManager = new SubListSubManager(null);

        Predicate<String> filter = subManager.processResourceListChange(ImmutableSet.of("foo"), ImmutableSet.of()).orElseThrow();

        assertThat(filter.test("foo")).isTrue();
        assertThat(filter.test("bar")).isFalse();
        assertThat(filter.test("baz")).isFalse();

        // xDS spec says envoy can sub twice if it has e.g. forgotten the resource for some reason
        // If this happens the spec says we should send the current state.
        filter = subManager.processResourceListChange(ImmutableSet.of("foo", "bar"), ImmutableSet.of()).orElseThrow();

        assertThat(filter.test("foo")).isTrue();
        assertThat(filter.test("bar")).isTrue();
        assertThat(filter.test("baz")).isFalse();

        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isFalse();
    }
    
    @Test
    public void testSubscribeRemove() {
        SubListSubManager subManager = new SubListSubManager(null);

        Predicate<String> filter = subManager.processResourceListChange(ImmutableSet.of("foo", "bar"), ImmutableSet.of()).orElseThrow();

        assertThat(filter.test("foo")).isTrue();
        assertThat(filter.test("bar")).isTrue();
        assertThat(filter.test("baz")).isFalse();

        filter = subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of("foo")).orElseThrow();

        assertThat(filter.test("foo")).isFalse();
        assertThat(filter.test("bar")).isFalse();
        assertThat(filter.test("baz")).isFalse();

        assertThat(subManager.isSubscribedTo("foo")).isFalse();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isFalse();
    }

    @Test
    public void testUnsubscribeAll() {
        SubListSubManager subManager = new SubListSubManager(null);

        subManager.processResourceListChange(ImmutableSet.of("foo", "bar"), ImmutableSet.of());

        Predicate<String> filter = subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of("foo", "bar")).orElseThrow();

        assertThat(filter.test("foo")).isFalse();
        assertThat(filter.test("bar")).isFalse();
        assertThat(filter.test("baz")).isFalse();
        assertThat(subManager.isSubscribedTo("foo")).isFalse();
        assertThat(subManager.isSubscribedTo("bar")).isFalse();
        assertThat(subManager.isSubscribedTo("baz")).isFalse();
    }

    @Test
    public void testUnsubscribeNotSubscribed() {
        SubListSubManager subManager = new SubListSubManager(null);

        subManager.processResourceListChange(ImmutableSet.of("foo", "bar"), ImmutableSet.of());

        // Nothing changes, so no filter returned.
        assertThat(subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of("baz"))).isNotPresent();

        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isFalse();
    }

    @Test
    public void testSubscribeNoChange() {
        SubListSubManager subManager = new SubListSubManager(null);

        subManager.processResourceListChange(ImmutableSet.of("foo"), ImmutableSet.of());

        // Nothing changes, so no filter returned.
        assertThat(subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of())).isNotPresent();

        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isFalse();
    }


    @Test
    public void testSubscribeToEmpty() {
        SubListSubManager subManager = new SubListSubManager(null);

        // Nothing changes, so no filter returned.
        assertThat(subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of())).isNotPresent();

        assertThat(subManager.isSubscribedTo("foo")).isFalse();
    }


    @Test
    public void testSubscribeUnsubscribeSameResourceThrows() {
        SubListSubManager subManager = new SubListSubManager(null);

        assertThatThrownBy(() -> subManager.processResourceListChange(ImmutableSet.of("foo"), ImmutableSet.of("foo"))).isInstanceOf(IllegalArgumentException.class);
    }

}
