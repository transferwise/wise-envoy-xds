package com.transferwise.envoy.e2e.delta;

public class Envoy121Test extends EnvoyIntTest {

    @Override
    protected String getEnvoyImageName() {
        return "envoyproxy/envoy:v1.21-latest";
    }

    @Override
    protected boolean hasBrokenWildcardReconnect() {
        return false;
    }

}
