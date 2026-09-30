package dev.outrigger.nullness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.outrigger.proxy.ServerAccess;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asks jdtls for a file's project classpath ({@code java.project.getClasspaths},
 * as vscode-java does). Output folders of other modules
 * ({@code target/classes}) are mapped back to their sources
 * ({@code src/main/java}).
 */
final class JdtlsClasspath implements Projects.ClasspathSource {

    private final ServerAccess server;

    JdtlsClasspath(ServerAccess server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Optional<Projects.Classpath>> classpath(String fileUri) {
        JsonArray arguments = new JsonArray();
        arguments.add(fileUri);
        arguments.add(new JsonPrimitive("{\"scope\":\"test\"}"));
        JsonObject params = new JsonObject();
        params.addProperty("command", "java.project.getClasspaths");
        params.add("arguments", arguments);
        return server.request("workspace/executeCommand", params).thenApply(JdtlsClasspath::parse);
    }

    static Optional<Projects.Classpath> parse(JsonElement result) {
        if (result == null || !result.isJsonObject() || !result.getAsJsonObject().has("projectRoot")) {
            return Optional.empty();
        }
        JsonObject classpath = result.getAsJsonObject();
        Path root = Path.of(URI.create(classpath.get("projectRoot").getAsString()));
        List<Path> jars = new ArrayList<>();
        List<Path> sourceRoots = new ArrayList<>();
        for (JsonElement element : classpath.getAsJsonArray("classpaths")) {
            Path entry = Path.of(element.getAsString());
            if (entry.toString().endsWith(".jar")) {
                if (Files.isRegularFile(entry)) {
                    jars.add(entry);
                }
            } else {
                sources(entry).filter(Files::isDirectory).ifPresent(sourceRoots::add);
            }
        }
        return Optional.of(new Projects.Classpath(root, jars, sourceRoots));
    }

    /** {@code <module>/target/classes} → {@code <module>/src/main/java}, likewise for tests. */
    private static Optional<Path> sources(Path output) {
        Path target = output.getParent();
        if (target == null || !target.getFileName().toString().equals("target")) {
            return Optional.empty();
        }
        return switch (output.getFileName().toString()) {
            case "classes" -> Optional.of(target.resolveSibling("src/main/java"));
            case "test-classes" -> Optional.of(target.resolveSibling("src/test/java"));
            default -> Optional.empty();
        };
    }
}
