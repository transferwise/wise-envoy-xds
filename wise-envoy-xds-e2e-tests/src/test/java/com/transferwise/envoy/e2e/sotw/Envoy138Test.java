package com.transferwise.envoy.e2e.sotw;

public class Envoy138Test extends EnvoyIntTest {

    @Override
    protected String getEnvoyImageName() {
        return "envoyproxy/envoy:v1.38-latest";
    }

    @Override
    protected boolean hasBrokenWildcardReconnect() {
        return false;
    }

}
