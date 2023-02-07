package com.transferwise.envoy.xds;

import com.google.rpc.Status;

public class ClientNackException extends RuntimeException {
    public ClientNackException(String responseNonce, Status errorDetail) {
        super("Client NACKed an update: " + responseNonce + " - " + errorDetail);
    }
}
