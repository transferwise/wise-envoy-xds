package com.transferwise.envoy.e2e.delta;

public class Envoy118Test extends EnvoyIntTest {

    @Override
    protected String getEnvoyImageName() {
        return "envoyproxy/envoy:v1.18-latest";
    }

    @Override
    protected boolean hasBrokenWildcardReconnect() {
        return true;
    }

}
