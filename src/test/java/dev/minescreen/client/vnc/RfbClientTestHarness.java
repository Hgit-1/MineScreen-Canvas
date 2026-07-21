package dev.minescreen.client.vnc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Loopback RFB 3.8/VNC-auth/raw-frame integration test for the real client worker. */
final class RfbClientTestHarness {
    private static final String PASSWORD = "mine1234";

    private RfbClientTestHarness() {
    }

    static void run() throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread worker = new Thread(() -> serve(server, serverFailure), "rfb-test-server");
            worker.start();
            try (RfbClient client = new RfbClient(
                    new RfbEndpoint("127.0.0.1", server.getLocalPort()), PASSWORD, 9, 5, 1024, false)) {
                client.start();
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (!client.receivedFramebufferUpdate() && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                if (!client.receivedFramebufferUpdate()) {
                    throw new AssertionError("RFB client produced no framebuffer: "
                            + client.state() + " / " + client.errorMessage());
                }
                if (client.width() != 2 || client.height() != 2) {
                    throw new AssertionError("Unexpected framebuffer dimensions");
                }
            }
            worker.join(2_000L);
            if (worker.isAlive()) {
                throw new AssertionError("Fake RFB server did not exit");
            }
            if (serverFailure.get() != null) {
                throw new AssertionError("Fake RFB server failed", serverFailure.get());
            }
        }
    }

    private static void serve(ServerSocket server, AtomicReference<Throwable> failure) {
        try (Socket socket = server.accept();
                DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            output.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            assertBytes(input.readNBytes(12), "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            output.writeByte(1);
            output.writeByte(2);
            output.flush();
            if (input.readUnsignedByte() != 2) {
                throw new AssertionError("Client did not select VNC auth");
            }
            byte[] challenge = new byte[16];
            for (int index = 0; index < challenge.length; index++) {
                challenge[index] = (byte) (index * 13 + 7);
            }
            output.write(challenge);
            output.flush();
            assertBytes(input.readNBytes(16), challengeResponse(challenge, PASSWORD));
            output.writeInt(0);
            output.flush();
            if (input.readUnsignedByte() != 1) {
                throw new AssertionError("Client did not request shared desktop");
            }
            output.writeShort(2);
            output.writeShort(2);
            output.write(new byte[16]);
            byte[] name = "MineScreen RFB test".getBytes(StandardCharsets.UTF_8);
            output.writeInt(name.length);
            output.write(name);
            output.flush();

            input.readNBytes(20); // SetPixelFormat
            if (input.readUnsignedByte() != 2) {
                throw new AssertionError("Expected SetEncodings");
            }
            input.readUnsignedByte();
            int encodingCount = input.readUnsignedShort();
            input.skipNBytes(encodingCount * 4L);
            if (input.readUnsignedByte() != 3) {
                throw new AssertionError("Expected FramebufferUpdateRequest");
            }
            input.skipNBytes(9);

            output.writeByte(0);
            output.writeByte(0);
            output.writeShort(1);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(2);
            output.writeShort(2);
            output.writeInt(0);
            output.write(new byte[] {
                    (byte) 255, 0, 0, 0, 0, (byte) 255, 0, 0,
                    0, 0, (byte) 255, 0, (byte) 255, (byte) 255, (byte) 255, 0
            });
            output.flush();
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private static byte[] challengeResponse(byte[] challenge, String password) throws Exception {
        byte[] key = Arrays.copyOf(password.getBytes(StandardCharsets.ISO_8859_1), 8);
        for (int index = 0; index < key.length; index++) {
            key[index] = reverseBits(key[index]);
        }
        Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
        return cipher.doFinal(challenge);
    }

    private static byte reverseBits(byte value) {
        int input = value & 0xFF;
        int output = 0;
        for (int bit = 0; bit < 8; bit++) {
            output = output << 1 | input & 1;
            input >>>= 1;
        }
        return (byte) output;
    }

    private static void assertBytes(byte[] actual, byte[] expected) {
        if (!Arrays.equals(actual, expected)) {
            throw new AssertionError("Protocol bytes differ");
        }
    }
}
