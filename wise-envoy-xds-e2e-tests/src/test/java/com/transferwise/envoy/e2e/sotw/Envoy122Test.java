package com.transferwise.envoy.e2e.sotw;

public class Envoy122Test extends EnvoyIntTest {

    @Override
    protected String getEnvoyImageName() {
        return "envoyproxy/envoy:v1.22-latest";
    }

    @Override
    protected boolean hasBrokenWildcardReconnect() {
        return false;
    }

}
