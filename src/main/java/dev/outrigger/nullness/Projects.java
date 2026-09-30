package dev.outrigger.nullness;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import dev.outrigger.library.ClassFiles;
import dev.outrigger.library.ImplementationIndex;
import dev.outrigger.library.LibraryNullness;
import dev.outrigger.library.Settings;
import dev.outrigger.proxy.Log;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The projects (Maven modules) of the files being analysed, found by source
 * folder. A project's classpath is loaded in the background the first time
 * one of its files is analysed; until then the file is analysed without it.
 */
final class Projects {

    /** Where a project's classpath comes from; jdtls in practice. */
    interface ClasspathSource {
        CompletableFuture<Optional<Classpath>> classpath(String fileUri);
    }

    /** The jars of a project, and the source folders of it and the modules it depends on. */
    record Classpath(Path root, List<Path> jars, List<Path> sourceRoots) {
    }

    /** A project ready for analysis. */
    record Project(Path root, List<Path> sourceRoots, TypeSolver jars, LibraryNullness nullness,
            boolean libraryWarningsInTests) {
    }

    private static final long RETRY_MILLIS = 15_000;

    private final ClasspathSource classpaths;
    private final Settings settings;
    private final Supplier<Optional<ImplementationIndex>> implementations;
    private final Consumer<Path> onReady;
    private final Map<Path, Object> bySourceRoot = new ConcurrentHashMap<>();
    private final Map<Path, Project> byRoot = new ConcurrentHashMap<>();
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "outrigger-projects");
        thread.setDaemon(true);
        return thread;
    });

    /** {@code onReady} is called with a project's root once its classpath is loaded. */
    Projects(ClasspathSource classpaths, Settings settings, Supplier<Optional<ImplementationIndex>> implementations,
            Consumer<Path> onReady) {
        this.classpaths = classpaths;
        this.settings = settings;
        this.implementations = implementations;
        this.onReady = onReady;
    }

    /** The project of a source folder if it is loaded; otherwise starts loading it. */
    Optional<Project> forSourceRoot(Path sourceRoot, String fileUri) {
        Object state = bySourceRoot.get(sourceRoot);
        if (state instanceof Project project) {
            return Optional.of(project);
        }
        boolean loading = state instanceof Loading;
        boolean failedRecently = state instanceof Failed failed
                && System.currentTimeMillis() - failed.at() < RETRY_MILLIS;
        if (!loading && !failedRecently) {
            bySourceRoot.put(sourceRoot, new Loading());
            classpaths.classpath(fileUri)
                    .thenAcceptAsync(classpath -> load(sourceRoot, classpath), background)
                    .exceptionally(error -> {
                        // e.g. jdtls hasn't imported the project yet: try again later
                        bySourceRoot.put(sourceRoot, new Failed(System.currentTimeMillis()));
                        return null;
                    });
        }
        return Optional.empty();
    }

    private void load(Path sourceRoot, Optional<Classpath> classpath) {
        if (classpath.isEmpty()) {
            bySourceRoot.put(sourceRoot, new Failed(System.currentTimeMillis()));
            return;
        }
        Classpath found = classpath.get();
        Project project = byRoot.computeIfAbsent(found.root(), root -> build(found));
        bySourceRoot.put(sourceRoot, project);
        onReady.accept(found.root());
    }

    private Project build(Classpath classpath) {
        long started = System.currentTimeMillis();
        CombinedTypeSolver jars = new CombinedTypeSolver();
        for (Path jar : classpath.jars()) {
            try {
                jars.add(new JarTypeSolver(jar));
            } catch (IOException | RuntimeException e) {
                // unreadable jar: its classes stay unknown
            }
        }
        jars.add(new ReflectionTypeSolver());
        LibraryNullness nullness = new LibraryNullness(new ClassFiles(classpath.jars()), implementations, settings);
        Log.info("loaded the classpath of " + classpath.root() + ": " + classpath.jars().size() + " jars, "
                + classpath.sourceRoots().size() + " source folders, " + (System.currentTimeMillis() - started) + "ms");
        return new Project(classpath.root(), classpath.sourceRoots(), jars, nullness,
                settings.libraryWarningsInTests());
    }

    private record Loading() {
    }

    private record Failed(long at) {
    }
}
