package dev.minescreen.client.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

/** Owns one isolated, muted, loopback-only compatibility browser process. */
final class ExternalBrowserProcess implements AutoCloseable {
    private static final Path RUNTIME_ROOT = FMLPaths.CONFIGDIR.get()
            .resolve("minescreen-browser-runtime").toAbsolutePath().normalize();
    private final Path executable;
    private final Path profileDirectory;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Process process;
    private int port;
    private volatile boolean closed;

    ExternalBrowserProcess(Path executable) {
        this.executable = executable;
        profileDirectory = RUNTIME_ROOT.resolve(UUID.randomUUID().toString()).normalize();
        if (!profileDirectory.startsWith(RUNTIME_ROOT)) {
            throw new IllegalStateException("Invalid browser runtime directory");
        }
        start();
    }

    PageTarget openPage(String url) {
        ensureAlive();
        String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/json/new?" + encoded);
        HttpRequest put = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<String> response = http.send(put,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                HttpRequest get = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8)).GET().build();
                response = http.send(get, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("compatibility browser rejected a new tab");
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            return new PageTarget(json.get("id").getAsString(),
                    URI.create(json.get("webSocketDebuggerUrl").getAsString()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to contact compatibility browser", exception);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Compatibility browser startup interrupted", interrupted);
        }
    }

    void closePage(String id) {
        if (id == null || id.isBlank() || port <= 0) return;
        try {
            http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/json/close/" + id)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException ignored) {
        }
    }

    boolean alive() {
        return !closed && process != null && process.isAlive();
    }

    private void start() {
        try {
            Files.createDirectories(profileDirectory);
            if (!tryStart("--headless=new")) {
                closeProcess();
                Files.deleteIfExists(profileDirectory.resolve("DevToolsActivePort"));
                if (!tryStart("--headless")) {
                    throw new IllegalStateException("Installed browser does not expose a compatible headless endpoint");
                }
            }
        } catch (IOException exception) {
            closeProcess();
            throw new IllegalStateException("Unable to start compatibility browser", exception);
        }
    }

    private boolean tryStart(String headlessArgument) throws IOException {
        List<String> command = new ArrayList<>(List.of(executable.toString(), headlessArgument,
                "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
                "--user-data-dir=" + profileDirectory,
                "--no-first-run", "--no-default-browser-check", "--disable-extensions",
                "--disable-sync", "--disable-quic", "--mute-audio", "--hide-scrollbars",
                "about:blank"));
        process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Path activePort = profileDirectory.resolve("DevToolsActivePort");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (System.nanoTime() < deadline && process.isAlive()) {
            if (Files.isRegularFile(activePort)) {
                try {
                    List<String> lines = Files.readAllLines(activePort, StandardCharsets.UTF_8);
                    if (!lines.isEmpty()) {
                        port = Integer.parseInt(lines.getFirst().trim());
                        return port > 0 && port <= 65535;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            try {
                Thread.sleep(40L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void ensureAlive() {
        if (!alive()) {
            throw new IllegalStateException("Compatibility browser exited unexpectedly");
        }
    }

    private void closeProcess() {
        Process current = process;
        process = null;
        if (current != null && current.isAlive()) {
            current.destroy();
            try {
                if (!current.waitFor(800L, TimeUnit.MILLISECONDS)) current.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        closeProcess();
        if (!profileDirectory.startsWith(RUNTIME_ROOT)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(profileDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    record PageTarget(String id, URI webSocketEndpoint) {
    }
}
