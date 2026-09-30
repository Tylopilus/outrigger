package dev.outrigger.nullness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.outrigger.document.PositionEncoding;
import dev.outrigger.document.TextDocument;
import dev.outrigger.library.ImplementationIndex;
import dev.outrigger.library.Settings;
import dev.outrigger.proxy.Feature;
import dev.outrigger.proxy.Log;
import dev.outrigger.proxy.ServerAccess;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Null analysis that Eclipse JDT lacks, applied to the diagnostics jdtls
 * publishes. See {@link NullnessAnalysis}.
 *
 * <p>Once attached to jdtls, it loads each project's classpath from jdtls to
 * look at library methods too, and indexes the implementation directories
 * from {@link Settings}. Both happen in the background; the diagnostics are
 * published again when they are done.
 */
public final class NullnessFeature implements Feature {

    private final Settings settings;
    private final CompletableFuture<Optional<ImplementationIndex>> implementations = new CompletableFuture<>();
    private Projects projects;

    public NullnessFeature() {
        this(Settings.load(), null);
    }

    /** With a classpath source instead of jdtls, for tests. */
    NullnessFeature(Settings settings, Projects.ClasspathSource classpaths) {
        this.settings = settings;
        if (classpaths != null) {
            this.projects = new Projects(classpaths, settings, this::implementations, root -> {
            });
            loadImplementations(() -> {
            });
        }
    }

    @Override
    public void attach(ServerAccess server) {
        projects = new Projects(new JdtlsClasspath(server), settings, this::implementations,
                root -> server.republish(uri -> isIn(uri, root)));
        loadImplementations(() -> server.republish(uri -> true));
    }

    @Override
    public boolean wantsDiagnostics(JsonArray diagnostics, boolean documentOpen) {
        if (documentOpen) {
            return true; // may add warnings of its own
        }
        for (JsonElement element : diagnostics) {
            JsonObject diagnostic = element.getAsJsonObject();
            String code = diagnostic.has("code") ? diagnostic.get("code").getAsString() : "";
            if (code.equals(NullnessAnalysis.POTENTIAL_NULL) || code.equals(NullnessAnalysis.DEAD_CODE)
                    || NullnessAnalysis.NULL_TYPE_MISMATCH.contains(code)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public JsonArray diagnostics(TextDocument document, JsonArray diagnostics, PositionEncoding encoding) {
        if (!document.uri().endsWith(".java")) {
            return diagnostics;
        }
        return NullnessAnalysis.of(document, encoding,
                        root -> projects == null ? Optional.empty() : projects.forSourceRoot(root, document.uri()))
                .map(analysis -> analysis.apply(diagnostics))
                .orElse(diagnostics);
    }

    private Optional<ImplementationIndex> implementations() {
        return implementations.getNow(Optional.empty());
    }

    private void loadImplementations(Runnable onLoaded) {
        if (settings.implementationDirs().isEmpty()) {
            implementations.complete(Optional.empty());
            return;
        }
        Thread thread = new Thread(() -> {
            long started = System.currentTimeMillis();
            try {
                ImplementationIndex index = ImplementationIndex.load(settings.implementationDirs(), settings.cacheDir());
                Log.info("indexed " + index.size() + " classes of " + settings.implementationDirs() + " in "
                        + (System.currentTimeMillis() - started) + "ms");
                implementations.complete(Optional.of(index));
                onLoaded.run();
            } catch (Exception e) {
                Log.error("indexing the implementation directories failed", e);
                implementations.complete(Optional.empty());
            }
        }, "outrigger-implementations");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean isIn(String uri, Path root) {
        try {
            return Path.of(URI.create(uri)).startsWith(root);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
