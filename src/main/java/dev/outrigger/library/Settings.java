package dev.outrigger.library;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Outrigger's settings in {@code ~/.config/outrigger} (or
 * {@code $OUTRIGGER_CONFIG_DIR}):
 *
 * <ul>
 * <li>{@code config.properties}: {@code implementations} lists directories,
 * separated by {@code :}, that are searched for jars with implementations of
 * library interfaces, e.g. the bundles of a local AEM
 * ({@code crx-quickstart/launchpad/felix}).</li>
 * <li>{@code nullness.txt}: lines like
 * {@code com.day.cq.wcm.api.PageManager#getPage nullable} (or
 * {@code nonnull}) that override what Outrigger finds out itself.</li>
 * </ul>
 * In {@code config.properties}, {@code libraryWarningsInTests=true} also
 * reports unchecked uses of nullable library methods in test sources, where
 * they're off by default (an unchecked {@code adaptTo} fails a test anyway).
 * <ul>
 * </ul>
 *
 * The implementation index is cached in {@code ~/.cache/outrigger} (or
 * {@code $OUTRIGGER_CACHE_DIR}).
 */
public record Settings(List<Path> implementationDirs, Map<String, Boolean> overrides, Path cacheDir,
        boolean libraryWarningsInTests) {

    public Settings(List<Path> implementationDirs, Map<String, Boolean> overrides, Path cacheDir) {
        this(implementationDirs, overrides, cacheDir, false);
    }

    public static Settings load() {
        Path home = Path.of(System.getProperty("user.home"));
        Path configDir = env("OUTRIGGER_CONFIG_DIR").map(Path::of).orElse(home.resolve(".config/outrigger"));
        Path cacheDir = env("OUTRIGGER_CACHE_DIR").map(Path::of).orElse(home.resolve(".cache/outrigger"));

        Properties properties = new Properties();
        Path config = configDir.resolve("config.properties");
        if (Files.isRegularFile(config)) {
            try (Reader reader = Files.newBufferedReader(config)) {
                properties.load(reader);
            } catch (IOException e) {
                // unreadable: defaults
            }
        }
        List<Path> implementationDirs = Arrays.stream(properties.getProperty("implementations", "").split(":"))
                .map(String::trim)
                .filter(dir -> !dir.isEmpty())
                .map(dir -> dir.startsWith("~/") ? home.resolve(dir.substring(2)) : Path.of(dir))
                .toList();

        Map<String, Boolean> overrides = new HashMap<>();
        Path nullness = configDir.resolve("nullness.txt");
        if (Files.isRegularFile(nullness)) {
            try {
                for (String line : Files.readAllLines(nullness)) {
                    String[] parts = line.replaceAll("#(?![\\w$]).*|^\\s*#.*", "").trim().split("\\s+");
                    if (parts.length == 2 && parts[0].contains("#")) {
                        overrides.put(parts[0], parts[1].equalsIgnoreCase("nullable"));
                    }
                }
            } catch (IOException e) {
                // unreadable: no overrides
            }
        }
        return new Settings(implementationDirs, overrides, cacheDir,
                Boolean.parseBoolean(properties.getProperty("libraryWarningsInTests", "false")));
    }

    /** The override for {@code owner#name}, if any; owner is an internal name. */
    public Optional<Boolean> override(String owner, String name) {
        return Optional.ofNullable(overrides.get(owner.replace('/', '.').replace('$', '.') + "#" + name));
    }

    private static Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank());
    }
}
