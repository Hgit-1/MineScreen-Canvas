package dev.minescreen.client.vnc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

/**
 * Client-local VNC secrets. This file is never read by common/server code and credentials are
 * never included in a payload. It is plaintext because classic RFB authentication itself is not a
 * secure password transport; users should protect the config directory and prefer a TLS tunnel.
 */
public final class VncCredentialStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type TYPE = new TypeToken<Map<String, Credential>>() {
    }.getType();
    private static Map<String, Credential> credentials;

    private VncCredentialStore() {
    }

    public static synchronized Credential get(UUID groupId) {
        ensureLoaded();
        return credentials.getOrDefault(groupId.toString(), new Credential("", ""));
    }

    public static synchronized void put(UUID groupId, Credential credential) {
        ensureLoaded();
        credentials.put(groupId.toString(), credential == null ? new Credential("", "") : credential);
        save();
    }

    public static synchronized void migrate(UUID oldGroupId, UUID newGroupId) {
        ensureLoaded();
        if (!credentials.containsKey(newGroupId.toString())) {
            Credential credential = credentials.remove(oldGroupId.toString());
            if (credential != null) {
                credentials.put(newGroupId.toString(), credential);
                save();
            }
        }
    }

    private static void ensureLoaded() {
        if (credentials != null) {
            return;
        }
        credentials = new HashMap<>();
        Path path = path();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, Credential> loaded = GSON.fromJson(reader, TYPE);
            if (loaded != null) {
                credentials.putAll(loaded);
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(credentials, TYPE, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config/minescreen-vnc-credentials.json");
    }

    public record Credential(String username, String password) {
        public Credential {
            username = username == null ? "" : username;
            password = password == null ? "" : password;
        }
    }
}
