package com.transferwise.envoy.example.state;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * A very simple representation of a service. In our example a service (cluster) has a name, and a set of endpoints
 */
@Value
@Builder
public class Service {

    String name;

    @Singular
    ImmutableSet<HostAndPort> endpoints;

}
