package com.transferwise.envoy.xds;

import static org.assertj.core.api.Assertions.assertThat;

import io.envoyproxy.envoy.config.core.v3.BuildVersion;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.type.v3.SemanticVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class NodeConfigTest {

    @ParameterizedTest
    @CsvSource({
        "envoy,1,17,0,true",
        "envoy,1,18,5,true",
        "envoy,1,19,0,false",
        "envoy,2,0,0,false",
        "bob,1,17,0,false"
    })
    public void testBugClientSendsResourceListOnReconnectToWildcard(String uaName, int major, int minor, int patch, boolean isBug) {

        Node node = Node.newBuilder()
            .setUserAgentName(uaName)
            .setUserAgentBuildVersion(BuildVersion.newBuilder()
                .setVersion(SemanticVersion.newBuilder()
                    .setMajorNumber(major)
                    .setMinorNumber(minor)
                    .setPatch(patch)
                    .build())
                .build())
            .build();

        assertThat(NodeConfig.forNode(node, XdsConfig.builder().build()).isBugClientSendsResourceListOnReconnectToWildcard()).isEqualTo(isBug);
    }

}
