package com.transferwise.envoy.example.config;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;

@Value
@Builder
public class ClientConfig {

    /**
     * Port on which the envoy instance is listening for http requests to forward.
     */
    @Default
    long listenPort = 8080;

}
