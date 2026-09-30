package dev.outrigger;

import dev.outrigger.nullness.NullnessFeature;
import dev.outrigger.proxy.Log;
import dev.outrigger.proxy.Proxy;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code outrigger [--] <server command...>}: starts the language server and
 * proxies the editor's stdin/stdout to it.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(args);
        if (!command.isEmpty() && command.getFirst().equals("--")) {
            command = command.subList(1, command.size());
        }
        if (command.isEmpty()) {
            System.err.println("usage: outrigger [--] <language server command...>");
            System.exit(2);
        }

        Process server = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        Log.info("started " + command.getFirst() + " (pid " + server.pid() + ")");

        Proxy proxy = new Proxy(System.in, System.out, server.getInputStream(), server.getOutputStream(),
                List.of(new NullnessFeature()));
        proxy.run();

        System.exit(server.waitFor());
    }
}
