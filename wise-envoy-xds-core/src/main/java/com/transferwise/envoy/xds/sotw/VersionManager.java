package com.transferwise.envoy.xds.sotw;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.primitives.UnsignedLong;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
class VersionManager {

    public VersionManager() {
        this(UnsignedLong.ONE);
    }

    @VisibleForTesting
    VersionManager(UnsignedLong initialVersion) {
        this.currentVersion = initialVersion;
    }

    private String currentNonce;

    private UnsignedLong acceptedVersion;
    private UnsignedLong sentVersion;

    private UnsignedLong currentVersion;

    public String getNext() {
        if (currentVersion.equals(UnsignedLong.MAX_VALUE)) {
            throw new IndexOutOfBoundsException("Overflowed version counter");
        }
        currentVersion = currentVersion.plus(UnsignedLong.ONE);
        return currentVersion.toString();
    }

    public boolean hasAcceptedVersion(String version) {
        if (acceptedVersion == null) {
            return false;
        }

        return acceptedVersion.compareTo(UnsignedLong.valueOf(version)) >= 0;
    }

    @VisibleForTesting
    UnsignedLong getSentVersion() {
        return sentVersion;
    }

    public boolean processUpdate(String responseNonce, String versionInfo) {
        if (sentVersion != null) {
            if (!currentNonce.equals(responseNonce)) {
                log.debug("Client sent stale nonce {}, ignoring", responseNonce == null ? "''" : responseNonce);
                return false;
            }

            if (Strings.isNullOrEmpty(versionInfo)) {
                log.error("Client rejected version {} and has no version to roll back to!", sentVersion);
            } else {
                UnsignedLong parsedVersion = UnsignedLong.valueOf(versionInfo);
                if (!sentVersion.equals(parsedVersion)) {
                    log.error("Client rejected version {} and rolled back to {}", sentVersion, versionInfo);
                } else {
                    log.debug("Client accepted version {}", sentVersion);
                    acceptedVersion = sentVersion;
                }
            }
        } else if (Strings.isNullOrEmpty(responseNonce)) {
            log.debug("Client sent nonce {} when we expected null. Probably this is a reconnect. Continuing!", responseNonce);
        }
        return true;
    }

    public boolean needsPush() {
        return (sentVersion == null || currentVersion.compareTo(sentVersion) > 0);
    }

    public String pushedVersion(String newVersion) {
        String nonce = String.valueOf(UUID.randomUUID());
        currentNonce = nonce;
        sentVersion = UnsignedLong.valueOf(newVersion);
        return nonce;
    }

}
