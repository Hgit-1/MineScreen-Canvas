package dev.minescreen.client.traffic;

import java.nio.charset.StandardCharsets;

/** Entry point for the memory-bounded template generator subprocess. */
public final class TrafficScriptWorker {
    private static final int MAX_INPUT_BYTES = 256 * 1024;

    private TrafficScriptWorker() {
    }

    public static void main(String[] args) {
        try {
            byte[] input = System.in.readNBytes(MAX_INPUT_BYTES + 1);
            if (input.length > MAX_INPUT_BYTES) throw new IllegalArgumentException("Script exceeds 256 KiB");
            String sourceName = args.length == 0 ? "template.js" : args[0];
            String output = TrafficScriptEngine.evaluate(new String(input, StandardCharsets.UTF_8),
                    sourceName);
            System.out.write(output.getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            String detail = message == null ? throwable.getClass().getSimpleName() : message;
            try {
                System.err.write(detail.getBytes(StandardCharsets.UTF_8));
                System.err.flush();
            } catch (java.io.IOException ignored) {
            }
            System.exit(2);
        }
    }
}
