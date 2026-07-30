package dev.minescreen.network;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Pure-Java safety/fragmentation regression checks for the multiplayer template channel. */
public final class TrafficTemplateSyncTestHarness {
    private TrafficTemplateSyncTestHarness() {
    }

    public static void main(String[] args) throws Exception {
        byte[] manifest = ("{" +
                "\"id\":\"jr_demo\",\"layout\":\"script_scene_v1\"," +
                "\"elements\":[{\"type\":\"text\",\"x\":0.1,\"y\":0.2," +
                "\"text\":\"${next}\",\"size\":1.0}]" +
                "}").getBytes(StandardCharsets.UTF_8);
        TrafficTemplateManifest.validate("jr_demo", manifest);
        String hash = TrafficTemplateManifest.sha256(manifest);
        require(hash.length() == 64 && TrafficTemplateManifest.validHash(hash),
                "sha256 format");
        int chunks = (manifest.length + TrafficTemplateManifest.CHUNK_BYTES - 1)
                / TrafficTemplateManifest.CHUNK_BYTES;
        byte[] joined = new byte[manifest.length];
        int offset = 0;
        for (int index = 0; index < chunks; index++) {
            int start = index * TrafficTemplateManifest.CHUNK_BYTES;
            int end = Math.min(manifest.length, start + TrafficTemplateManifest.CHUNK_BYTES);
            byte[] part = Arrays.copyOfRange(manifest, start, end);
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        require(Arrays.equals(manifest, joined), "chunk join");
        boolean rejected = false;
        try {
            TrafficTemplateManifest.validate("jr_demo", "{\"layout\":\"javascript\"}"
                    .getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        require(rejected, "arbitrary JavaScript manifest rejected");

        byte[] forwardCompatible = ("{" +
                "\"id\":\"jr_demo\",\"layout\":\"script_scene_v1\"," +
                "\"manifest_version\":2," +
                "\"placeholder_defaults\":{\"operator_name\":\"Demo Railway\"}," +
                "\"elements\":[" +
                "{\"type\":\"text\",\"x\":0.1,\"y\":0.2,\"text\":\"${operator_name}\"}," +
                "{\"type\":\"future_glow\",\"optional\":true,\"strength\":0.8}" +
                "]}").getBytes(StandardCharsets.UTF_8);
        TrafficTemplateManifest.validate("jr_demo", forwardCompatible);

        byte[] dynamicText = ("{" +
                "\"id\":\"notice_demo\",\"layout\":\"script_scene_v1\"," +
                "\"elements\":[],\"text_sequence\":{" +
                "\"loop\":true,\"default_duration_ticks\":80,\"frames\":[" +
                "{\"text\":\"Welcome aboard\",\"duration_ticks\":80," +
                "\"transition\":\"fade\",\"align\":\"center\"}," +
                "{\"text\":\"Next stop: Central\",\"duration_ticks\":60," +
                "\"transition\":\"slide_left\",\"align\":\"right\"}]}}")
                .getBytes(StandardCharsets.UTF_8);
        TrafficTemplateManifest.validate("notice_demo", dynamicText);

        byte[] thickLine = ("{" +
                "\"id\":\"jr_demo\"," +
                "\"layout\":\"script_scene_v1\"," +
                "\"elements\":[{\"type\":\"line\",\"x\":0.1,\"y\":0.5," +
                "\"x2\":0.9,\"y2\":0.5,\"size\":24," +
                "\"vendor_cap\":\"round\"}]," +
                "\"vendor_style\":{\"font\":\"future:sans\"}" +
                "}").getBytes(StandardCharsets.UTF_8);
        TrafficTemplateManifest.validate("jr_demo", thickLine);

        boolean requiredFutureLayerRejected = false;
        try {
            TrafficTemplateManifest.validate("jr_demo", ("{" +
                    "\"id\":\"jr_demo\",\"layout\":\"script_scene_v1\"," +
                    "\"elements\":[{\"type\":\"future_glow\"}]}").getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException expected) {
            requiredFutureLayerRejected = true;
        }
        require(requiredFutureLayerRejected, "unknown required scene element rejected");
        System.out.println("trafficTemplateSyncTest=passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
