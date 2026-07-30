package dev.minescreen.client.vnc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercises the real socket worker against a deterministic RFB 3.8 endpoint. This is deliberately
 * separate from the Tight decoder fixtures: it covers version/security negotiation, password
 * challenge generation, pixel-format/encoding requests, a raw framebuffer update, and outbound
 * pointer/key control without requiring a user's VNC server.
 */
public final class RfbLoopbackTestHarness {
    private static final String TEST_CREDENTIAL = "loopback";
    private static final byte[] CHALLENGE = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    private RfbLoopbackTestHarness() {
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        CountDownLatch authenticated = new CountDownLatch(1);
        CountDownLatch pointerReceived = new CountDownLatch(1);
        CountDownLatch keyReceived = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread endpoint = Thread.ofPlatform()
                    .daemon(true)
                    .name("minescreen-rfb-loopback-server")
                    .start(() -> serve(server, authenticated, pointerReceived, keyReceived,
                            serverFailure));

            try (RfbClient client = new RfbClient(
                    new RfbEndpoint("127.0.0.1", server.getLocalPort()),
                    TEST_CREDENTIAL, 9, 5, 1_024, false)) {
                client.setTargetFps(60);
                client.start();
                awaitFrame(client);
                require(client.width() == 2 && client.height() == 2,
                        "Unexpected loopback desktop size: " + client.width() + "x" + client.height());
                require(authenticated.await(2, TimeUnit.SECONDS),
                        "RFB password challenge was not completed");

                client.pointerEvent(1, 1, 1);
                client.keyEvent(true, 0x61);
                require(pointerReceived.await(2, TimeUnit.SECONDS),
                        "RFB pointer event was not received");
                require(keyReceived.await(2, TimeUnit.SECONDS),
                        "RFB key event was not received");
            }

            endpoint.join(2_000L);
            Throwable failure = serverFailure.get();
            if (failure != null) {
                throw new AssertionError("Loopback RFB endpoint failed", failure);
            }
        }

        System.out.println("rfbLoopbackTest=passed; auth=VNC; desktop=2x2; pointer=true; key=true");
    }

    private static void serve(ServerSocket server, CountDownLatch authenticated,
            CountDownLatch pointerReceived, CountDownLatch keyReceived,
            AtomicReference<Throwable> failure) {
        try (Socket socket = server.accept();
                DataInputStream input = new DataInputStream(
                        new BufferedInputStream(socket.getInputStream()));
                DataOutputStream output = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream()))) {
            socket.setSoTimeout(5_000);
            output.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            require("RFB 003.008\n".equals(new String(input.readNBytes(12),
                    StandardCharsets.US_ASCII)), "Client did not negotiate RFB 3.8");

            output.writeByte(1);
            output.writeByte(2); // VNC authentication
            output.flush();
            require(input.readUnsignedByte() == 2, "Client did not select VNC authentication");
            output.write(CHALLENGE);
            output.flush();
            byte[] response = input.readNBytes(16);
            require(response.length == 16 && !allZero(response),
                    "Client returned an invalid VNC challenge response");
            output.writeInt(0);
            output.flush();
            authenticated.countDown();

            require(input.readUnsignedByte() == 1, "Client did not request a shared desktop");
            writeServerInit(output);
            readClientPixelFormat(input);
            readClientEncodings(input);
            require(input.readUnsignedByte() == 3, "Expected initial framebuffer request");
            input.skipNBytes(9);
            writeRawFrame(output);

            while (pointerReceived.getCount() != 0 || keyReceived.getCount() != 0) {
                int message = input.readUnsignedByte();
                switch (message) {
                    case 3 -> {
                        input.skipNBytes(9);
                        writeEmptyFrame(output);
                    }
                    case 4 -> {
                        boolean down = input.readUnsignedByte() != 0;
                        input.skipNBytes(2);
                        int key = input.readInt();
                        require(down && key == 0x61, "Unexpected RFB key event");
                        keyReceived.countDown();
                    }
                    case 5 -> {
                        int mask = input.readUnsignedByte();
                        int x = input.readUnsignedShort();
                        int y = input.readUnsignedShort();
                        require(mask == 1 && x == 1 && y == 1,
                                "Unexpected RFB pointer event");
                        pointerReceived.countDown();
                    }
                    default -> throw new AssertionError("Unexpected client RFB message: " + message);
                }
            }
        } catch (EOFException ignored) {
            if (pointerReceived.getCount() != 0 || keyReceived.getCount() != 0) {
                failure.compareAndSet(null, ignored);
            }
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

    private static void writeServerInit(DataOutputStream output) throws Exception {
        output.writeShort(2);
        output.writeShort(2);
        output.writeByte(32);
        output.writeByte(24);
        output.writeByte(0);
        output.writeByte(1);
        output.writeShort(255);
        output.writeShort(255);
        output.writeShort(255);
        output.writeByte(0);
        output.writeByte(8);
        output.writeByte(16);
        output.write(new byte[3]);
        byte[] name = "MineScreen loopback".getBytes(StandardCharsets.UTF_8);
        output.writeInt(name.length);
        output.write(name);
        output.flush();
    }

    private static void readClientPixelFormat(DataInputStream input) throws Exception {
        require(input.readUnsignedByte() == 0, "Expected SetPixelFormat");
        input.skipNBytes(19);
    }

    private static void readClientEncodings(DataInputStream input) throws Exception {
        require(input.readUnsignedByte() == 2, "Expected SetEncodings");
        input.readUnsignedByte();
        int count = input.readUnsignedShort();
        require(count == 7, "Unexpected RFB encoding count: " + count);
        input.skipNBytes(count * 4L);
    }

    private static void writeRawFrame(DataOutputStream output) throws Exception {
        output.writeByte(0);
        output.writeByte(0);
        output.writeShort(1);
        output.writeShort(0);
        output.writeShort(0);
        output.writeShort(2);
        output.writeShort(2);
        output.writeInt(0);
        output.write(new byte[] {
                (byte) 255, 0, 0, 0,
                0, (byte) 255, 0, 0,
                0, 0, (byte) 255, 0,
                (byte) 255, (byte) 255, (byte) 255, 0
        });
        output.flush();
    }

    private static void writeEmptyFrame(DataOutputStream output) throws Exception {
        output.writeByte(0);
        output.writeByte(0);
        output.writeShort(0);
        output.flush();
    }

    private static void awaitFrame(RfbClient client) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!client.receivedFramebufferUpdate() && client.errorMessage() == null
                && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        require(client.receivedFramebufferUpdate(),
                "No loopback framebuffer received; state=" + client.state()
                        + "; error=" + client.errorMessage());
    }

    private static boolean allZero(byte[] value) {
        for (byte next : value) {
            if (next != 0) return false;
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
