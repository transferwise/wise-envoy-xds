package com.transferwise.envoy.example.config;

import com.transferwise.envoy.xds.XdsConfig;
import com.transferwise.envoy.xds.api.ClientConfigProvider;
import io.envoyproxy.envoy.config.core.v3.Node;

public class StaticClientConfigSource implements ClientConfigProvider<ClientConfig> {

    @Override
    public XdsConfig<ClientConfig> lookup(Node node) {

        ClientConfig.ClientConfigBuilder clientConfigBuilder = ClientConfig.builder();

        if (node.hasMetadata() && node.getMetadata().containsFields("my_listen_port")) {
            // As an example, we let the client pass us the port it claims to have a http connection manager listening on.
            // We use this in the RouteConfiguration.
            clientConfigBuilder.listenPort(
                (long) node.getMetadata().getFieldsOrThrow("my_listen_port").getNumberValue()
            );
        }
        // A real client config provider would probably also look up a bunch of stuff from other
        // configuration sources too.

        return XdsConfig.<ClientConfig>builder()
            .clientDetails(clientConfigBuilder.build())
            .build();
    }

}
