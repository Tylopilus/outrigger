package dev.outrigger.proxy;

/**
 * Logging to stderr. stdout carries the LSP stream and must never be written to.
 * Editors usually collect a server's stderr in their LSP log.
 */
public final class Log {

    private Log() {
    }

    public static void info(String message) {
        System.err.println("[outrigger] " + message);
    }

    public static void error(String message, Throwable error) {
        System.err.println("[outrigger] ERROR " + message + ": " + error);
    }
}
