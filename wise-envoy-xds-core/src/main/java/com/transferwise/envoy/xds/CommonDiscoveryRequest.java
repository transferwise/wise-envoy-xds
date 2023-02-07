package com.transferwise.envoy.xds;

import com.google.protobuf.Message;
import com.google.rpc.Status;
import io.envoyproxy.envoy.config.core.v3.Node;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
/*
 * Shared metadata between DiscoveryRequest and DeltaDiscoveryRequest, a missing "superclass"
 */
public class CommonDiscoveryRequest<RequestT extends Message> {
    Node node;
    String typeUrl;
    RequestT message;
    Status errorDetail;
}
