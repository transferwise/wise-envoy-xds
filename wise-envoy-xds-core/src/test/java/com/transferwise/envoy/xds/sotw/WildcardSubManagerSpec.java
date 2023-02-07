package com.transferwise.envoy.xds.sotw;

import com.google.common.collect.ImmutableMap;
import com.transferwise.envoy.xds.sotw.WildcardSubManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("Duplicates")
@SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "Mockito rule")
public class WildcardSubManagerSpec {

    @Test
    public void testNotSubscribed() {
        WildcardSubManager subManager = new WildcardSubManager();

        Map<String, Object> subs = ImmutableMap.of(
                "foo", new Object(),
                "bar", new Object()
        );

        assertThat(subManager.filterSubs(subs).entrySet()).isEmpty();
    }

    @Test
    public void testSubscribeToWildcard() {
        WildcardSubManager subManager = new WildcardSubManager();
        assertThat(subManager.processResourceListChange(Collections.emptyList())).isPresent();
        assertThat(subManager.isSubscribed()).isTrue();

        Map<String, Object> subs = ImmutableMap.of(
                "foo", new Object(),
                "bar", new Object()
        );

        assertThat(subManager.filterSubs(subs)).isEqualTo(subs);

        assertThat(subManager.processResourceListChange(Collections.emptyList())).isNotPresent();
        assertThat(subManager.filterSubs(subs)).isEqualTo(subs);

    }

    @Test
    public void testSubscribeToNonWildcardNotPermitted() {
        WildcardSubManager subManager = new WildcardSubManager();
        assertThatThrownBy(() -> subManager.processResourceListChange(Collections.singletonList("foo"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    public void testReSubscribeToNonWildcardNotPermitted() {
        WildcardSubManager subManager = new WildcardSubManager();
        assertThat(subManager.processResourceListChange(Collections.emptyList())).isPresent();
        assertThatThrownBy(() -> subManager.processResourceListChange(Collections.singletonList("foo"))).isInstanceOf(RuntimeException.class);
    }


}
