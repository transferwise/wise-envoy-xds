package com.transferwise.envoy.e2e.sotw;

public class Envoy119Test extends EnvoyIntTest {

    @Override
    protected String getEnvoyImageName() {
        return "envoyproxy/envoy:v1.19-latest";
    }

    @Override
    protected boolean hasBrokenWildcardReconnect() {
        return false;
    }

}
