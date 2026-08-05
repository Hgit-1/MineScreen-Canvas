package dev.minescreen.client.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.gui.screens.Screen;

/** Optional, reflection-only view of MCEF's own downloader; no MCEF class is linked eagerly. */
public final class McefDownloadProgressBridge {
    private static volatile boolean observed;

    private McefDownloadProgressBridge() {
    }

    public static boolean isDownloader(Screen screen) {
        boolean result = screen != null && screen.getClass().getName()
                .equals("com.cinemamod.mcef.internal.MCEFDownloaderMenu");
        if (result) observed = true;
        return result;
    }

    public static Progress snapshot() {
        if (!observed) return Progress.absent();
        try {
            ClassLoader loader = McefDownloadProgressBridge.class.getClassLoader();
            Class<?> type = Class.forName("com.cinemamod.mcef.internal.MCEFDownloadListener",
                    false, loader);
            Field field = type.getField("INSTANCE");
            Object listener = field.get(null);
            Method taskMethod = type.getMethod("getTask");
            Method progressMethod = type.getMethod("getProgress");
            Method doneMethod = type.getMethod("isDone");
            Method failedMethod = type.getMethod("isFailed");
            String task = String.valueOf(taskMethod.invoke(listener));
            Object value = progressMethod.invoke(listener);
            double progress = value instanceof Number number ? number.doubleValue() : 0.0D;
            // MCEF versions have exposed both 0..1 and 0..100 progress values.
            if (progress > 1.0D) progress /= 100.0D;
            return new Progress(true, clamp(progress), task,
                    Boolean.TRUE.equals(doneMethod.invoke(listener)),
                    Boolean.TRUE.equals(failedMethod.invoke(listener)));
        } catch (Throwable ignored) {
            return new Progress(true, 0.0D, "MCEF Chromium runtime", false, false);
        }
    }

    public static void clearObservation() {
        observed = false;
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record Progress(boolean present, double progress, String task, boolean done,
            boolean failed) {
        static Progress absent() {
            return new Progress(false, 0.0D, "", false, false);
        }

        public boolean active() {
            return present && !done && !failed;
        }
    }
}
