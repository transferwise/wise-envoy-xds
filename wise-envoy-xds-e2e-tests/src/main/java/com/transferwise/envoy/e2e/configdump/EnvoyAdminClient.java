package com.transferwise.envoy.e2e.configdump;

import com.google.common.net.HostAndPort;
import com.google.common.reflect.ClassPath;
import com.google.protobuf.Any;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.TypeRegistry;
import io.envoyproxy.envoy.admin.v3.ConfigDump;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import lombok.SneakyThrows;

/**
 * Very simple client for the Envoy http admin interface.
 * Useful for writing end to end tests, where you might want to check envoy's config matches what you expected.
 */
public class EnvoyAdminClient {

    private final HostAndPort hostAndPort;
    private final TypeRegistry typeRegistry;

    private final JsonFormat.Parser parser;

    public EnvoyAdminClient(HostAndPort hostAndPort) throws IOException {
        this.hostAndPort = hostAndPort;
        this.typeRegistry = buildTypeRegistry();
        this.parser = JsonFormat.parser().ignoringUnknownFields()
            .usingTypeRegistry(typeRegistry);
    }

    /**
     * Unpack an Any without checked exceptions.
     * @param thing Any to unpack
     * @param clazz Class to unpack as
     * @param <T> Type of the unpackaged message (should match clazz)
     * @return The unpacked message
     * @throws RuntimeException if things go wrong.
     */
    @SneakyThrows
    public static <T extends Message> T sneakyUnpack(Any thing, Class<T> clazz) {
        return thing.unpack(clazz);
    }

    private TypeRegistry buildTypeRegistry() throws IOException {
        // Surely there is a better way than this?
        TypeRegistry.Builder typeRegistryBuilder = TypeRegistry.newBuilder();
        ClassPath cp = ClassPath.from(ClassLoader.getSystemClassLoader());
        cp.getTopLevelClassesRecursive("io.envoyproxy.envoy").forEach(classinfo -> {
            Class<?> clazz;
            try {
                clazz = Class.forName(classinfo.getName());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            try {
                Method gd = clazz.getMethod("getDescriptor");
                if (gd.getReturnType().getName().equals("com.google.protobuf.Descriptors$Descriptor")) {
                    typeRegistryBuilder.add((Descriptors.Descriptor) gd.invoke(null));
                }
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
                // Ignored
            }
        });
        return typeRegistryBuilder.build();
    }

    /**
     * Call the config_dump endpoint.
     * @return The config dump
     * @throws IOException if there was an IO error trying to call envoy's admin interface
     */
    public TypedEnvoyConfigDump configDump() throws IOException {
        URLConnection configDumper = new URL("http://" + hostAndPort.toString() + "/config_dump").openConnection();
        String asJson;
        try (InputStream response = configDumper.getInputStream()) {
            asJson = new String(response.readAllBytes(), StandardCharsets.UTF_8);
        }
        ConfigDump.Builder dumpBuilder = ConfigDump.newBuilder();
        parser.merge(asJson, dumpBuilder);
        return new TypedEnvoyConfigDump(dumpBuilder.build());
    }


}
