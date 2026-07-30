package dev.minescreen.client.traffic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Launches the import-time JS evaluator in a disposable, memory-bounded child JVM. */
final class TrafficScriptSandbox {
    private static final long MAX_SCRIPT_BYTES = 256L * 1024L;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    // Windows antivirus/class loading can be slow immediately after the nested jar is built. The
    // guest itself still has a 5 second execution budget; fifteen seconds is only the process-start
    // and outer-deadlock watchdog, while memory remains hard-capped at 64 MiB.
    private static final long PROCESS_TIMEOUT_SECONDS = 15L;
    private static final String RHINO_RESOURCE = "/META-INF/minescreen/script/rhino-1.9.1.jar";
    private static final String[] WORKER_CLASSES = {
            "/dev/minescreen/client/traffic/TrafficScriptWorker.class",
            "/dev/minescreen/client/traffic/TrafficScriptEngine.class",
            "/dev/minescreen/client/traffic/TrafficScriptEngine$1.class",
            "/dev/minescreen/client/traffic/TrafficScriptEngine$Budget.class"
    };

    private TrafficScriptSandbox() {
    }

    static JsonObject evaluate(Path scriptFile) throws IOException {
        Path source = scriptFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_SCRIPT_BYTES) {
            throw new IOException("Template script is missing or exceeds 256 KiB");
        }
        byte[] script = Files.readAllBytes(source);
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-Xms16m", "-Xmx64m",
                "-XX:MaxMetaspaceSize=64m", "-XX:+ExitOnOutOfMemoryError",
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-cp",
                workerClasspath(),
                TrafficScriptWorker.class.getName(), source.getFileName().toString());
        builder.redirectErrorStream(true);
        retainSafeWindowsEnvironment(builder.environment());
        builder.directory(Path.of(System.getProperty("java.io.tmpdir", ".")).toFile());
        Process process = builder.start();
        CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
            try (var input = process.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8_192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    total += count;
                    if (total > MAX_OUTPUT_BYTES) throw new IOException("Script output exceeds 256 KiB");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });
        IOException writeFailure = null;
        try (var stdin = process.getOutputStream()) {
            stdin.write(script);
        } catch (IOException exception) {
            // The worker may fail during class loading before it begins reading stdin. Continue to
            // collect its output so callers receive the actual error instead of Windows' opaque
            // "The pipe has been ended" message.
            writeFailure = exception;
        }
        boolean finished;
        try {
            finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Template script import was interrupted", exception);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Template script exceeded the 15 second process limit");
        }
        byte[] output;
        try {
            output = outputFuture.get(1L, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IOException("Unable to read template script output", exception);
        }
        String text = new String(output, StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            String detail = text.isBlank() && writeFailure != null
                    ? writeFailure.getMessage() : text;
            throw new IOException("Template script rejected: " + safeMessage(detail),
                    writeFailure);
        }
        if (writeFailure != null) {
            throw new IOException("Unable to send template script to worker: "
                    + safeMessage(text), writeFailure);
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IOException("generate() must return a JSON object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Template script returned invalid JSON", exception);
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String workerClasspath() throws IOException {
        return extractedWorker().toString() + java.io.File.pathSeparator + extractedRhino();
    }

    /**
     * NeoForge can load the mod through a virtual union: URL which an external JVM cannot use as a
     * class path. Package the four dependency-light worker classes into a real temporary jar.
     */
    private static Path extractedWorker() throws IOException {
        Path directory = runtimeDirectory();
        Path target = directory.resolve("minescreen-script-worker.jar");
        byte[] packaged;
        try (var bytes = new ByteArrayOutputStream(); var jar = new JarOutputStream(bytes)) {
            for (String resource : WORKER_CLASSES) {
                try (var input = TrafficScriptSandbox.class.getResourceAsStream(resource)) {
                    if (input == null) throw new IOException("Missing worker class " + resource);
                    jar.putNextEntry(new JarEntry(resource.substring(1)));
                    input.transferTo(jar);
                    jar.closeEntry();
                }
            }
            jar.finish();
            packaged = bytes.toByteArray();
        }
        installRuntimeFile(directory, target, packaged, "worker-");
        return target;
    }

    private static Path extractedRhino() throws IOException {
        Path directory = runtimeDirectory();
        Path target = directory.resolve("rhino-1.9.1.jar");
        byte[] packaged;
        try (var input = TrafficScriptSandbox.class.getResourceAsStream(RHINO_RESOURCE)) {
            if (input == null) throw new IOException("Packaged Rhino worker resource is missing");
            packaged = input.readAllBytes();
        }
        installRuntimeFile(directory, target, packaged, "rhino-");
        return target;
    }

    private static Path runtimeDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", "."),
                "minescreen-script-runtime").toAbsolutePath().normalize();
    }

    private static void installRuntimeFile(Path directory, Path target, byte[] packaged,
            String temporaryPrefix) throws IOException {
        Files.createDirectories(directory);
        if (Files.isRegularFile(target) && Files.size(target) == packaged.length
                && java.util.Arrays.equals(sha256(Files.readAllBytes(target)), sha256(packaged))) {
            return;
        }
        Path temporary = Files.createTempFile(directory, temporaryPrefix, ".tmp");
        try {
            Files.write(temporary, packaged);
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void retainSafeWindowsEnvironment(Map<String, String> environment) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            environment.clear();
            return;
        }
        Map<String, String> retained = new java.util.HashMap<>();
        for (String key : new String[] {"SystemRoot", "WINDIR", "TEMP", "TMP"}) {
            String value = environment.get(key);
            if (value != null && !value.isBlank()) retained.put(key, value);
        }
        environment.clear();
        environment.putAll(retained);
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "worker failed without details";
        String oneLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240);
    }
}
