package com.transferwise.envoy.xds.sotw;

import com.google.common.primitives.UnsignedLong;
import com.transferwise.envoy.xds.sotw.VersionManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SuppressWarnings("Duplicates")
@SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "Mockito rule")
public class VersionManagerSpec {

    @Test
    public void testNoneSent() {
        VersionManager versionManager = new VersionManager();
        assertThat(versionManager.needsPush()).isTrue();
    }

    @Test
    public void testAcceptsVersion() {
        VersionManager versionManager = new VersionManager();
        assertThat(versionManager.processUpdate("", "")).isTrue();
        assertThat(versionManager.needsPush()).isTrue();
        final String sentNonce = versionManager.pushedVersion("1");
        assertThat(versionManager.hasAcceptedVersion("1")).isFalse();
        assertThat(versionManager.getSentVersion()).isEqualTo(UnsignedLong.valueOf(1));
        assertThat(versionManager.needsPush()).isFalse();
        assertThat(versionManager.processUpdate(sentNonce, "1")).isTrue(); // accept that version
        assertThat(versionManager.hasAcceptedVersion("1")).isTrue();
        assertThat(versionManager.getSentVersion()).isEqualTo(UnsignedLong.valueOf(1));;
        assertThat(versionManager.needsPush()).isFalse();
    }

    @Test
    public void testRejectsInitialVersion() {
        VersionManager versionManager = new VersionManager();
        versionManager.processUpdate("", "");
        final String sentNonce = versionManager.pushedVersion("1");
        assertThat(versionManager.processUpdate(sentNonce, "")).isTrue(); // reject that version
        assertThat(versionManager.getSentVersion()).isEqualTo(UnsignedLong.valueOf(1));;
        assertThat(versionManager.hasAcceptedVersion("1")).isFalse();
        assertThat(versionManager.needsPush()).isFalse();
    }

    @Test
    public void testsRejectsThenAccepts() {
        VersionManager versionManager = new VersionManager();
        versionManager.processUpdate("", "");
        String sentNonce = versionManager.pushedVersion("1");
        versionManager.processUpdate(sentNonce, ""); // reject that version

        versionManager.getNext();
        assertThat(versionManager.needsPush()).isTrue();
        sentNonce = versionManager.pushedVersion("2");
        assertThat(versionManager.getSentVersion()).isEqualTo(UnsignedLong.valueOf(2));
        assertThat(versionManager.hasAcceptedVersion("1")).isFalse();

        assertThat(versionManager.needsPush()).isFalse();

        assertThat(versionManager.processUpdate(sentNonce, "2")).isTrue(); // accept that version
        assertThat(versionManager.hasAcceptedVersion("2")).isTrue();
        assertThat(versionManager.getSentVersion()).isEqualTo(UnsignedLong.valueOf(2));
        assertThat(versionManager.needsPush()).isFalse();


    }

    private String getToNonInitialState(VersionManager versionManager) {
        versionManager.processUpdate("", "");
        versionManager.needsPush();
        String firstNonce = versionManager.pushedVersion("1");
        versionManager.processUpdate(firstNonce, "1");
        return firstNonce;
    }

    @Test
    public void testRejectsVersion() {
        VersionManager versionManager = new VersionManager();
        getToNonInitialState(versionManager);
        String nonce = versionManager.pushedVersion("2");

        assertThat(versionManager.processUpdate(nonce, "1")).isTrue();
        assertThat(versionManager.getSentVersion()).isEqualTo(UnsignedLong.valueOf(2));
        assertThat(versionManager.hasAcceptedVersion("1")).isTrue();
        assertThat(versionManager.hasAcceptedVersion("2")).isFalse();
        assertThat(versionManager.needsPush()).isFalse();
    }

    @Test
    public void testClientSendsOutOfDateVersion() {
        VersionManager versionManager = new VersionManager();
        final String nonce = getToNonInitialState(versionManager);

        final String newNonce = versionManager.pushedVersion("2");
        assertThat(versionManager.processUpdate(nonce, "1")).isFalse(); // e.g. resource update at original version
        assertThat(versionManager.hasAcceptedVersion("1")).isTrue();
        assertThat(versionManager.getSentVersion()).isEqualTo(UnsignedLong.valueOf(2));
        assertThat(versionManager.needsPush()).isFalse();

        assertThat(versionManager.processUpdate(newNonce, "2")).isTrue(); // tries again with new nonce and version
        assertThat(versionManager.hasAcceptedVersion("2")).isTrue();
        assertThat(versionManager.getSentVersion()).isEqualTo(UnsignedLong.valueOf(2));


    }

    @Test
    public void testVersionWraps() {
        VersionManager versionManager = new VersionManager(UnsignedLong.MAX_VALUE.minus(UnsignedLong.ONE));
        String ver = versionManager.getNext();
        assertThat(versionManager.processUpdate("", "")).isTrue();
        String sentNonce = versionManager.pushedVersion(ver);
        assertThat(versionManager.processUpdate(sentNonce, "" + ver)).isTrue(); // accept that version
        assertThat(versionManager.hasAcceptedVersion(ver)).isTrue();

        assertThatThrownBy(versionManager::getNext).isInstanceOf(IndexOutOfBoundsException.class);
    }

}
