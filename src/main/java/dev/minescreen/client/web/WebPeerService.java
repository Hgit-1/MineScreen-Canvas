package dev.minescreen.client.web;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.ScreenGroup;
import dev.minescreen.network.WebPeerDirectoryPayload;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/** Owns the one process-wide direct TCP listener used by all distributed WEB sessions. */
public final class WebPeerService {
    static final int MAGIC = 0x4D535750; // MSWP
    static final int VERSION = 1;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, WebPeerSession> SESSIONS = new HashMap<>();
    private static final Map<UUID, WebPeerDirectoryPayload> PENDING = new HashMap<>();
    private static ServerSocket listener;
    private static Thread acceptThread;
    private static int listenPort;

    private WebPeerService() {
    }

    static synchronized int listenPort() {
        return listenPort;
    }

    static synchronized WebPeerSession open(ScreenGroup group, McefBrowserSession browser) {
        if (!MineScreenConfig.WEB_PEER_DISTRIBUTION.get()
                || dev.minescreen.client.ClientSecurityPolicy.unrestrictedSingleplayer()) {
            return null;
        }
        if (!ensureListener()) {
            return null;
        }
        WebPeerSession session = new WebPeerSession(group, browser);
        WebPeerSession replaced = SESSIONS.put(group.groupId(), session);
        if (replaced != null) {
            replaced.shutdown(false);
        }
        WebPeerDirectoryPayload pending = PENDING.remove(group.groupId());
        if (pending != null) {
            session.updateDirectory(pending);
        }
        dev.minescreen.client.network.ClientNetworkState.sendWebPeerAnnouncement(
                group, listenPort, true);
        return session;
    }

    static synchronized void close(WebPeerSession session, ScreenGroup group) {
        if (SESSIONS.remove(group.groupId(), session)) {
            dev.minescreen.client.network.ClientNetworkState.sendWebPeerAnnouncement(
                    group, listenPort, false);
        }
        session.shutdown(false);
    }

    public static void acceptDirectory(WebPeerDirectoryPayload directory) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            synchronized (WebPeerService.class) {
                WebPeerSession session = SESSIONS.get(directory.screenId());
                if (session == null) {
                    PENDING.put(directory.screenId(), directory);
                } else {
                    session.updateDirectory(directory);
                }
            }
        });
    }

    public static synchronized void shutdown() {
        for (WebPeerSession session : SESSIONS.values()) {
            session.shutdown(false);
        }
        SESSIONS.clear();
        PENDING.clear();
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
        }
        listener = null;
        acceptThread = null;
        listenPort = 0;
    }

    private static boolean ensureListener() {
        if (listener != null && !listener.isClosed()) {
            return true;
        }
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(MineScreenConfig.WEB_PEER_LISTEN_PORT.get()));
            listener = server;
            listenPort = server.getLocalPort();
            acceptThread = new Thread(() -> acceptLoop(server), "minescreen-web-peer-listener");
            acceptThread.setDaemon(true);
            acceptThread.start();
            return true;
        } catch (IOException exception) {
            LOGGER.error("Unable to open MineScreen WEB peer listener", exception);
            listener = null;
            listenPort = 0;
            return false;
        }
    }

    private static void acceptLoop(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5_000);
                DataInputStream input = new DataInputStream(
                        new BufferedInputStream(socket.getInputStream()));
                int magic = input.readInt();
                int version = input.readUnsignedByte();
                UUID screenId = new UUID(input.readLong(), input.readLong());
                UUID token = new UUID(input.readLong(), input.readLong());
                UUID playerId = new UUID(input.readLong(), input.readLong());
                WebPeerSession session;
                synchronized (WebPeerService.class) {
                    session = SESSIONS.get(screenId);
                }
                if (magic != MAGIC || version != VERSION || session == null
                        || !session.accept(socket, input, token, playerId)) {
                    socket.close();
                }
            } catch (IOException exception) {
                if (!server.isClosed()) {
                    LOGGER.debug("Rejected MineScreen WEB peer connection", exception);
                }
            }
        }
    }
}
