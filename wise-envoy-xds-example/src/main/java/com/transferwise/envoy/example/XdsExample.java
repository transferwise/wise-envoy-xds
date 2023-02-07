package com.transferwise.envoy.example;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import com.transferwise.envoy.xds.AggregatedDiscoveryService;
import com.transferwise.envoy.xds.api.DiscoveryServiceManagerMetrics;
import com.transferwise.envoy.xds.api.utils.MergingStateBacklog;
import com.transferwise.envoy.example.config.ClientConfig;
import com.transferwise.envoy.example.config.StaticClientConfigSource;
import com.transferwise.envoy.example.configbuilder.ClusterConfigBuilder;
import com.transferwise.envoy.example.configbuilder.ClusterLoadAssignmentConfigBuilder;
import com.transferwise.envoy.example.configbuilder.RouteConfigurationConfigBuilder;
import com.transferwise.envoy.example.state.Service;
import com.transferwise.envoy.example.state.SimpleUpdate;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class XdsExample {

    private static final Splitter argSplitter = Splitter.on(' ').trimResults().omitEmptyStrings();

    // Everything below this point is handling the CLI commands to allow modification of the mesh state.

    public static void main(String[] args) throws IOException, InterruptedException {

        ClusterManager clusterManager = new ClusterManager();

        // Wire up the aggregated discovery service implementation
        AggregatedDiscoveryService<SimpleUpdate, ClientConfig> ads = new AggregatedDiscoveryService<>(
            clusterManager, // Cluster managers track the network state and tell us when it changes
            ImmutableList.of(// ConfigBuilders generate envoy configuration (xDS messages) based on network state changes and the client config
                new ClusterConfigBuilder(),
                new ClusterLoadAssignmentConfigBuilder(),
                new RouteConfigurationConfigBuilder()
            ),
            new StaticClientConfigSource(), // ClientConfigProviders provide per-client configuration to the config builders
            ImmutableList.of(), // Our example has no event listeners, but you can use these for things like tracking connected clients
            MergingStateBacklog.factory(), // Strategy for handling a backlog of network state updates.
            DiscoveryServiceManagerMetrics.METRICS_DISABLED // Use the NOOP metrics factory.
        );

        // Start a grpc server for our ADS using the standard grpc ServerBuilder
        // (Use whatever framework you normally use for your grpc services, this is intended as a
        // very simple example!)
        Server server = ServerBuilder.forPort(6566).addService(ads).build();
        server.start();

        // Everything after this point is just providing the CLI

        mainLoop(clusterManager);
        System.out.println("Initiating graceful shutdown...");
        server.shutdown();
        server.awaitTermination(1, TimeUnit.MINUTES);
        System.out.println("Terminating.");
        server.shutdownNow();
        server.awaitTermination();
    }

    private static void cmdList(ClusterManager clusterManager) {
        System.out.println("Services:");
        clusterManager.getState().forEach((k, v) -> {
            System.out.print('\t');
            System.out.println(k);
        });
    }

    private static void cmdShow(ClusterManager clusterManager, String name) {
        System.out.println("Service: " + name);
        Service service = clusterManager.getState().get(name);
        if (service == null) {
            System.out.println("\tNot Found");
        } else {
            service.getEndpoints().forEach(e -> {
                System.out.print('\t');
                System.out.println(e);
            });
        }
    }

    private static void cmdAdd(ClusterManager clusterManager, String name, String endpoint) {
        HostAndPort addr = HostAndPort.fromString(endpoint);
        if (!InetAddresses.isInetAddress(addr.getHost())) {
            throw new IllegalArgumentException("endpoints must be ip:port format");
        }
        clusterManager.addEndpoint(name, addr);
    }

    private static void cmdRemove(ClusterManager clusterManager, String name, String endpoint) {
        HostAndPort addr = HostAndPort.fromString(endpoint);
        if (!InetAddresses.isInetAddress(addr.getHost())) {
            throw new IllegalArgumentException("endpoints must be ip:port format");
        }
        clusterManager.removeEndpoint(name, addr);
    }

    private static void cmdRemove(ClusterManager clusterManager, String name) {
        clusterManager.removeService(name);
    }

    private static void mainLoop(ClusterManager clusterManager) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
        System.out.println("CLI driven service mesh!");
        cliIntro();
        while (true) {
            System.out.print("> ");
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            List<String> parts = argSplitter.splitToList(line);
            if (parts.isEmpty()) {
                continue;
            }
            try {
                switch (parts.get(0)) {
                    case "list" -> {
                        if (parts.size() != 1) {
                            throw new IllegalArgumentException("Incorrect argument count.");
                        }
                        cmdList(clusterManager);
                    }
                    case "show" -> {
                        if (parts.size() != 2) {
                            throw new IllegalArgumentException("Incorrect argument count.");
                        }
                        cmdShow(clusterManager, parts.get(1));
                    }
                    case "add" -> {
                        if (parts.size() != 3) {
                            throw new IllegalArgumentException("Incorrect argument count.");
                        }
                        cmdAdd(clusterManager, parts.get(1), parts.get(2));
                    }
                    case "remove" -> {
                        if (parts.size() > 3) {
                            throw new IllegalArgumentException("Incorrect argument count.");
                        }
                        if (parts.size() == 2) {
                            cmdRemove(clusterManager, parts.get(1));
                        } else {
                            cmdRemove(clusterManager, parts.get(1), parts.get(2));
                        }
                    }
                    case "quit" -> {
                        return;
                    }
                    case "help" -> cliIntro();
                    default -> {
                        System.out.println("Unknown command");
                        cliIntro();
                    }
                }
            } catch (IllegalArgumentException e) {
                System.out.println("Failed to parse command: " + e.getMessage());
                e.printStackTrace();
                cliIntro();
            }
        }

    }

    private static void cliIntro() {
        System.out.println("Commands:");
        System.out.println("  list - list all service names");
        System.out.println("  show <name> - show endpoints for service by name");
        System.out.println("  add <name> <ip:port> - add a new endpoint to a service, creating it if needed");
        System.out.println("  remove <name> <ip:port> - remove an endpoint from a service");
        System.out.println("  remove <name> - remove a service");
        System.out.println("  quit - shutdown");
    }

}
