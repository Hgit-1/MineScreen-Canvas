package dev.minescreen.client.vnc;

/** Manual loopback probe. Password is accepted only through RFB_TEST_PASSWORD. */
public final class RfbExternalProbe {
    private RfbExternalProbe() {
    }

    public static void main(String[] args) throws Exception {
        String password = System.getenv("RFB_TEST_PASSWORD");
        if (password == null) {
            throw new IllegalArgumentException("RFB_TEST_PASSWORD is required");
        }
        try (RfbClient client = new RfbClient(new RfbEndpoint("127.0.0.1", 5900),
                password, 9, 5, 67_108_864, false)) {
            client.start();
            long deadline = System.nanoTime() + 25_000_000_000L;
            while (!client.receivedFramebufferUpdate() && client.errorMessage() == null
                    && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            System.out.println("state=" + client.state());
            System.out.println("desktop=" + client.width() + "x" + client.height());
            System.out.println("firstFrame=" + client.receivedFramebufferUpdate());
            System.out.println("error=" + client.errorMessage());
            if (!client.receivedFramebufferUpdate()) {
                throw new AssertionError("No external VNC framebuffer received");
            }
        }
    }
}
