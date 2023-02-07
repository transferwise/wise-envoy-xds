package com.transferwise.envoy.e2e;

import static com.transferwise.envoy.e2e.configdump.EnvoyAdminClient.sneakyUnpack;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.transferwise.envoy.e2e.assertions.EnvoyAssert;
import com.transferwise.envoy.e2e.configdump.EnvoyAdminClient;
import com.transferwise.envoy.e2e.configdump.TypedEnvoyConfigDump;
import com.transferwise.envoy.e2e.utils.ConversationLogger;
import com.transferwise.envoy.e2e.utils.ConversationLogger.Conversation;
import com.transferwise.envoy.e2e.utils.DiscoveryServiceManagerEventListener;
import com.transferwise.envoy.e2e.utils.EnvoyContainer;
import com.transferwise.envoy.e2e.utils.GrpcXdsV3ProtocolLoggingInterceptor;
import com.transferwise.envoy.xds.AggregatedDiscoveryService;
import com.transferwise.envoy.xds.TypeUrl;
import com.transferwise.envoy.xds.api.utils.MergingStateBacklog;
import com.transferwise.envoy.example.ClusterManager;
import com.transferwise.envoy.example.config.ClientConfig;
import com.transferwise.envoy.example.config.StaticClientConfigSource;
import com.transferwise.envoy.example.configbuilder.ClusterConfigBuilder;
import com.transferwise.envoy.example.configbuilder.ClusterLoadAssignmentConfigBuilder;
import com.transferwise.envoy.example.configbuilder.RouteConfigurationConfigBuilder;
import com.transferwise.envoy.example.state.SimpleUpdate;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ExtendWith(MockitoExtension.class)
public abstract class BaseEnvoyIntTest<AsserterT extends EnvoyAssert<AsserterT>> {

    @Container
    private final EnvoyContainer envoy = new EnvoyContainer(getEnvoyImageName())
        .withClasspathResourceMapping(getEnvoyConfigFileName(), "/envoy-custom.yaml", BindMode.READ_ONLY)
        .withCommand("-l", "debug", "-c", "/envoy-custom.yaml")
        .withExposedPorts(9901)
        .withAccessToHost(true)
        .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(BaseEnvoyIntTest.class)));
    Server server = null;
    EnvoyAdminClient adminClient = null;

    protected abstract String getEnvoyImageName();

    protected abstract String getEnvoyConfigFileName();

    protected abstract boolean hasBrokenWildcardReconnect();

    protected abstract InstanceOfAssertFactory<Conversation, AsserterT> assertFactory();

    @BeforeEach
    public void createAdminClient() throws IOException {
        adminClient = new EnvoyAdminClient(HostAndPort.fromParts(envoy.getHost(), envoy.getMappedPort(9901)));
    }

    @AfterEach
    public void stopServer() throws InterruptedException {
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination();
            server = null;
        }
    }

    protected abstract AsserterT envoyAssertThat(Conversation conversation);

    protected AggregatedDiscoveryService<SimpleUpdate, ClientConfig> configureAds(ClusterManager clusterManager, List<DiscoveryServiceManagerEventListener> conversations) {
        return new AggregatedDiscoveryService<>(
            clusterManager, // Cluster managers track the network state and tell us when it changes
            ImmutableList.of(// ConfigBuilders generate envoy configuration (xDS messages) based on network state changes and the client config
                new ClusterConfigBuilder(),
                new ClusterLoadAssignmentConfigBuilder(),
                new RouteConfigurationConfigBuilder()
            ),
            new StaticClientConfigSource(), // ClientConfigProviders provide per-client configuration to the config builders
            ImmutableList.of(), // Our example has no event listeners, but you can use these for things like tracking connected clients
            MergingStateBacklog.factory(), // Strategy for handling a backlog of network state updates.
            () -> {
                DiscoveryServiceManagerEventListener listener = new DiscoveryServiceManagerEventListener();
                conversations.add(listener);
                return listener;
            }
        );
    }

    @Test
    public void simpleConversation() throws IOException {
        ClusterManager clusterManager = new ClusterManager();

        List<DiscoveryServiceManagerEventListener> conversations = Collections.synchronizedList(new ArrayList<>());

        AggregatedDiscoveryService<SimpleUpdate, ClientConfig> ads = configureAds(clusterManager, conversations);

        ConversationLogger conversationInterceptor = new ConversationLogger();
        server = ServerBuilder.forPort(6566).addService(ads).intercept(conversationInterceptor).intercept(new GrpcXdsV3ProtocolLoggingInterceptor()).build();
        server.start();
        org.testcontainers.Testcontainers.exposeHostPorts(6566);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(
            () -> assertThat(conversationInterceptor.getConversations())
                .isNotEmpty()
                .first(assertFactory())
                    .hadConversationsWith(TypeUrl.CDS.getTypeUrl(), TypeUrl.RDS.getTypeUrl())
        );
        await().atMost(30, TimeUnit.SECONDS).until(() -> conversations.size() > 0 && conversations.get(0).isIdle());

        envoyAssertThat(conversationInterceptor.getConversations().get(0)).nothingSentUnasked().allWereAcked();
        assertThat(conversations.get(0).getPushed()).isZero(); // We have not pushed any changes yet.

        clusterManager.setEndpoints("foo", HostAndPort.fromString("127.0.0.5"), HostAndPort.fromString("127.0.0.6"));

        await().atMost(30, TimeUnit.SECONDS).until(() -> conversations.get(0).getPushed() > 0 && conversations.get(0).isIdle());

        assertThat(conversations).hasSize(1);
        assertThat(conversationInterceptor.getConversations()).hasSize(1);

        envoyAssertThat(conversationInterceptor.getConversations().get(0)).nothingSentUnasked().allWereAcked();

        TypedEnvoyConfigDump dump = adminClient.configDump();

        assertThat(dump.getRoutesConfigDump().getDynamicRouteConfigsCount()).isEqualTo(1);
        assertThat(getClusters(dump)).extracting(Cluster::getName).containsExactlyInAnyOrder("foo");

    }

    @Test
    public void envoyReconnect() throws IOException, InterruptedException {
        ClusterManager clusterManager = new ClusterManager();

        List<DiscoveryServiceManagerEventListener> conversations = Collections.synchronizedList(new ArrayList<>());

        AggregatedDiscoveryService<SimpleUpdate, ClientConfig> ads = configureAds(clusterManager, conversations);

        ConversationLogger conversationInterceptor = new ConversationLogger();
        server = ServerBuilder.forPort(6566).addService(ads).intercept(conversationInterceptor).build();
        server.start();
        org.testcontainers.Testcontainers.exposeHostPorts(6566);
        {
            await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(conversationInterceptor.getConversations()).isNotEmpty().first(assertFactory()).hadConversationsWith(TypeUrl.CDS.getTypeUrl(), TypeUrl.RDS.getTypeUrl()));
            await().atMost(30, TimeUnit.SECONDS).until(() -> conversations.size() > 0 && conversations.get(0).isIdle());

            envoyAssertThat(conversationInterceptor.getConversations().get(0)).nothingSentUnasked().allWereAcked();

            TypedEnvoyConfigDump dump = adminClient.configDump();
            assertThat(dump.getRoutesConfigDump().getDynamicRouteConfigsCount()).isEqualTo(1);
            assertThat(dump.getClustersConfigDump().getDynamicActiveClustersCount()).isEqualTo(0);
            assertThat(conversations.get(0).getPushed()).isZero();
        }

        clusterManager.setEndpoints(ImmutableMap.of(
            "foo", ImmutableList.of(HostAndPort.fromString("127.0.0.5"), HostAndPort.fromString("127.0.0.6")),
            "bar", ImmutableList.of(HostAndPort.fromString("127.0.0.7"), HostAndPort.fromString("127.0.0.8"))
        ));

        {
            await().atMost(30, TimeUnit.SECONDS).until(() -> conversations.get(0).getPushed() > 0 && conversations.get(0).isIdle());

            assertThat(conversations).hasSize(1);
            assertThat(conversationInterceptor.getConversations()).hasSize(1);

            envoyAssertThat(conversationInterceptor.getConversations().get(0)).nothingSentUnasked().allWereAcked();

            TypedEnvoyConfigDump dump = adminClient.configDump();
            assertThat(dump.getRoutesConfigDump().getDynamicRouteConfigsCount()).isEqualTo(1);
            assertThat(getClusters(dump)).extracting(Cluster::getName).containsExactlyInAnyOrder("foo", "bar");
        }

        // Restart the grpc service, keeping the cluster manager, etc, intact.
        // This forces envoy to reconnect to us.
        server.shutdownNow();
        server.awaitTermination();

        // While envoy's definitely not connected, remove a service entirely.
        // When envoy reconnects and sends us the list of stuff it knew about, we should immediately tell it remove the lost service.
        clusterManager.removeService("bar");

        server = ServerBuilder.forPort(6566).addService(ads).intercept(conversationInterceptor).intercept(new GrpcXdsV3ProtocolLoggingInterceptor()).build();
        server.start();
        org.testcontainers.Testcontainers.exposeHostPorts(6566);

        {
            await().atMost(30, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(conversationInterceptor.getConversations()).hasSize(2).last(assertFactory()).hadConversationsWith(TypeUrl.CDS.getTypeUrl(), TypeUrl.RDS.getTypeUrl(), TypeUrl.EDS.getTypeUrl()));
            await().atMost(30, TimeUnit.SECONDS).until(() -> conversations.size() > 1 && conversations.get(1).isIdle());

            envoyAssertThat(conversationInterceptor.getConversations().get(1)).nothingSentUnasked().allWereAcked();

            TypedEnvoyConfigDump dump = adminClient.configDump();
            assertThat(dump.getRoutesConfigDump().getDynamicRouteConfigsCount()).isEqualTo(1);
            assertThat(getClusters(dump)).extracting(Cluster::getName).containsExactlyInAnyOrder("foo");
            assertThat(conversations.get(1).getPushed()).isZero();
        }

        clusterManager.setEndpoints("baz", HostAndPort.fromString("127.0.0.9"), HostAndPort.fromString("127.0.0.10"));

        {
            await().atMost(30, TimeUnit.SECONDS).until(() -> conversations.get(1).getPushed() > 0 && conversations.get(1).isIdle());

            envoyAssertThat(conversationInterceptor.getConversations().get(1)).nothingSentUnasked(hasBrokenWildcardReconnect()).allWereAcked();

            TypedEnvoyConfigDump dump = adminClient.configDump();
            assertThat(getClusters(dump)).extracting(Cluster::getName).containsExactlyInAnyOrder("foo", "baz");
        }

    }

    private List<Cluster> getClusters(TypedEnvoyConfigDump dump) {
        return dump.getClustersConfigDump().getDynamicActiveClustersList().stream()
            .map(c -> sneakyUnpack(c.getCluster(), Cluster.class)) // Collect the names of each of the clusters.
            .collect(Collectors.toList());
    }

}
