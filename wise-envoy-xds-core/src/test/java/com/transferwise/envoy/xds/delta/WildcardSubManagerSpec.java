package com.transferwise.envoy.xds.delta;

import com.google.common.collect.ImmutableSet;
import com.transferwise.envoy.xds.NodeConfig;
import com.transferwise.envoy.xds.delta.WildcardSubManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;


@SuppressWarnings("Duplicates")
@SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "Mockito rule")
public class WildcardSubManagerSpec {

    @Test
    public void testNotSubscribed() {
        WildcardSubManager subManager = new WildcardSubManager(NodeConfig.builder().build());

        assertThat(subManager.isSubscribedTo("foo")).isFalse();
        assertThat(subManager.isSubscribedTo("bar")).isFalse();
    }

    @Test
    public void testSubscribeEmptyFirstMessageIsWildcard() {
        WildcardSubManager subManager = new WildcardSubManager(NodeConfig.builder().build());

        Predicate<String> wouldSendUpdates = subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of()).orElseThrow();

        assertThat(wouldSendUpdates).accepts("foo", "bar");
        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
    }

    @Test
    public void testSubscribeStarIsWildcard() {
        WildcardSubManager subManager = new WildcardSubManager(NodeConfig.builder().build());

        Predicate<String> wouldSendUpdates = subManager.processResourceListChange(ImmutableSet.of("*"), ImmutableSet.of()).orElseThrow();

        assertThat(wouldSendUpdates).accepts("foo", "bar").rejects("*"); // The wildcard itself is not a real resource name
        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("*")).isFalse();
    }


    @Test
    public void testSubscribeMoreThanOnce() {
        WildcardSubManager subManager = new WildcardSubManager(NodeConfig.builder().build());

        Predicate<String> wouldSendUpdates = subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of()).orElseThrow();
        assertThat(wouldSendUpdates).accepts("foo", "bar");

        // Nothing changes, so no filter returned.
        assertThat(subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of())).isNotPresent();

        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
    }

    @Test
    public void testSubscribeWithResourceNamesSubscribesToResourcesByName() {
        WildcardSubManager subManager = new WildcardSubManager(NodeConfig.builder().build());

        Predicate<String> wouldSendUpdates = subManager.processResourceListChange(ImmutableSet.of("foo", "bar"), ImmutableSet.of()).orElseThrow();
        assertThat(wouldSendUpdates).accepts("foo", "bar").rejects("baz");


        // Unsubscribe from foo
        wouldSendUpdates = subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of("foo")).orElseThrow();
        assertThat(wouldSendUpdates).rejects("foo", "bar", "baz");


        assertThat(subManager.isSubscribedTo("foo")).isFalse();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isFalse();
    }

    @Test
    public void testBugClientSendsResourceListOnReconnectToWildcard() {
        // Prior to 1.19 a reconnecting envoy would send a resource list on connection to a wildcard DS if it already had state for that DS (e.g. if it was reconnecting to the control plane.)
        // While the spec says this should be treated as a normal SubList style subscribe, Envoy actually wanted a wildcard subscription, so we have a bug flag to allow this.
        WildcardSubManager subManager = new WildcardSubManager(NodeConfig.builder().bugClientSendsResourceListOnReconnectToWildcard(true).build());

        Predicate<String> wouldSendUpdates = subManager.processResourceListChange(ImmutableSet.of("foo", "bar"), ImmutableSet.of()).orElseThrow();
        assertThat(wouldSendUpdates).accepts("foo", "bar", "baz");


        // Nothing changes, so no filter returned.
        assertThat(subManager.processResourceListChange(ImmutableSet.of(), ImmutableSet.of("foo"))).isNotPresent();

        assertThat(wouldSendUpdates).accepts("foo", "bar", "baz");
    }


    @Test
    public void testSubUnsubSubWildcard() {
        WildcardSubManager subManager = new WildcardSubManager(NodeConfig.builder().build());

        Predicate<String> wouldSendUpdates = subManager.processResourceListChange(ImmutableSet.of("*", "foo"), ImmutableSet.of()).orElseThrow();
        assertThat(wouldSendUpdates).accepts("foo", "bar", "baz", "cat");
        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isTrue();
        assertThat(subManager.isSubscribedTo("cat")).isTrue();

        assertThat(subManager.processResourceListChange(ImmutableSet.of("bar"), ImmutableSet.of())).isEmpty();
        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isTrue();
        assertThat(subManager.isSubscribedTo("cat")).isTrue();

        wouldSendUpdates = subManager.processResourceListChange(ImmutableSet.of("baz"), ImmutableSet.of("*")).orElseThrow();
        assertThat(wouldSendUpdates).accepts("baz").rejects("foo", "bar", "cat"); // Only baz actually got changed by this update, it was already subscribed to foo and bar.
        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isTrue();
        assertThat(subManager.isSubscribedTo("cat")).isFalse();

        wouldSendUpdates = subManager.processResourceListChange(ImmutableSet.of("*"), ImmutableSet.of()).orElseThrow();
        assertThat(wouldSendUpdates).accepts("cat").rejects("foo", "bar", "baz"); // We were already subscribed to foo, bar, baz, so it's not a change for them.
        assertThat(subManager.isSubscribedTo("foo")).isTrue();
        assertThat(subManager.isSubscribedTo("bar")).isTrue();
        assertThat(subManager.isSubscribedTo("baz")).isTrue();
        assertThat(subManager.isSubscribedTo("cat")).isTrue();
    }

}
