package dev.minescreen.client;

import dev.minescreen.MineScreenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/** Resolves the explicit native-config single-player trust override. */
public final class ClientSecurityPolicy {
    private ClientSecurityPolicy() {
    }

    public static boolean unrestrictedSingleplayer() {
        if (!MineScreenConfig.UNRESTRICTED_SINGLEPLAYER.get()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        // Opening to LAN changes the trust boundary, so all normal URL/IP gates apply again.
        return minecraft.hasSingleplayerServer() && server != null && !server.isPublished();
    }

    public static boolean localFilesAllowed() {
        return unrestrictedSingleplayer() || MineScreenConfig.ALLOW_FILE_PROTOCOL.get();
    }
}
