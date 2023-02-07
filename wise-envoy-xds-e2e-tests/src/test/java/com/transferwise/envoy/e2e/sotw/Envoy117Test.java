package com.transferwise.envoy.e2e.sotw;

public class Envoy117Test extends EnvoyIntTest {

    @Override
    protected String getEnvoyImageName() {
        return "envoyproxy/envoy:v1.17-latest";
    }

    @Override
    protected boolean hasBrokenWildcardReconnect() {
        return true;
    }

}
