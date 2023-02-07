package com.transferwise.envoy.xds;

import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DiscoveryServiceOrderer {

    public static <T extends Message, StateUpdT> List<DiscoveryService<T, StateUpdT>> sort(List<TypeUrl> order, Map<TypeUrl, DiscoveryService<T, StateUpdT>> discoveryServices) {
        List<DiscoveryService<T, StateUpdT>> orderedDiscoveryServices = new ArrayList<>(order.size());
        order.forEach(t -> orderedDiscoveryServices.add(discoveryServices.get(t)));
        return orderedDiscoveryServices;
    }

}
