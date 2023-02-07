package com.transferwise.envoy.xds.sotw;

import com.google.common.collect.ImmutableMap;
import com.transferwise.envoy.xds.sotw.SubListSubManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("Duplicates")
@SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "Mockito rule")
public class SubListSubManagerSpec {

    @Test
    public void testNotSubscribed() {
        SubListSubManager subManager = new SubListSubManager();
        assertThat(subManager.getSubs()).isEmpty();

        Map<String, Object> subs = ImmutableMap.of(
                "foo", new Object(),
                "bar", new Object()
        );

        assertThat(subManager.filterSubs(subs).entrySet()).isEmpty();
    }

    @Test
    public void testSubscribeToEmpty() {
        SubListSubManager subManager = new SubListSubManager();
        assertThat(subManager.processResourceListChange(Collections.emptyList())).isNotPresent();
        assertThat(subManager.getSubs()).isEmpty();

        Map<String, Object> subs = ImmutableMap.of(
                "foo", new Object(),
                "bar", new Object()
        );

        assertThat(subManager.filterSubs(subs).entrySet()).isEmpty();
    }

    @Test
    public void testSubscribe() {
        SubListSubManager subManager = new SubListSubManager();
        assertThat(subManager.processResourceListChange(Collections.singletonList("foo"))).isPresent();
        assertThat(subManager.getSubs()).contains("foo");

        Map<String, Object> subs = ImmutableMap.of(
                "foo", new Object(),
                "bar", new Object()
        );

        assertThat(subManager.filterSubs(subs).keySet()).contains("foo");

        assertThat(subManager.processResourceListChange(Collections.singletonList("foo"))).isNotPresent();
        assertThat(subManager.filterSubs(subs).keySet()).contains("foo");


    }

    @Test
    public void testSubscribeReplace() {
        SubListSubManager subManager = new SubListSubManager();
        assertThat(subManager.processResourceListChange(Collections.singletonList("foo"))).isPresent();
        assertThat(subManager.getSubs()).contains("foo");

        Map<String, Object> subs = ImmutableMap.of(
                "foo", new Object(),
                "bar", new Object()
        );

        assertThat(subManager.filterSubs(subs).keySet()).contains("foo");

        assertThat(subManager.processResourceListChange(Collections.singletonList("bar"))).isPresent();
        assertThat(subManager.getSubs()).contains("bar");
        assertThat(subManager.filterSubs(subs).keySet()).contains("bar");

    }

    @Test
    public void testSubscribeMore() {
        SubListSubManager subManager = new SubListSubManager();
        assertThat(subManager.processResourceListChange(Collections.singletonList("foo"))).isPresent();
        assertThat(subManager.getSubs()).contains("foo");

        Map<String, Object> subs = ImmutableMap.of(
                "foo", new Object(),
                "bar", new Object()
        );

        assertThat(subManager.filterSubs(subs).keySet()).contains("foo");

        assertThat(subManager.processResourceListChange(new ArrayList<>(subs.keySet()))).isPresent();
        assertThat(subManager.getSubs()).containsExactlyInAnyOrder("foo", "bar");
        assertThat(subManager.filterSubs(subs).keySet()).containsExactlyInAnyOrder("foo", "bar");

    }

    @Test
    public void testUnsubscribeAll() {
        SubListSubManager subManager = new SubListSubManager();
        assertThat(subManager.processResourceListChange(Collections.singletonList("foo"))).isPresent();
        assertThat(subManager.getSubs()).contains("foo");

        Map<String, Object> subs = ImmutableMap.of(
                "foo", new Object(),
                "bar", new Object()
        );

        assertThat(subManager.filterSubs(subs).keySet()).contains("foo");

        assertThat(subManager.processResourceListChange(Collections.emptyList())).isPresent();
        assertThat(subManager.getSubs()).isEmpty();
        assertThat(subManager.filterSubs(subs).keySet()).isEmpty();

    }


}
