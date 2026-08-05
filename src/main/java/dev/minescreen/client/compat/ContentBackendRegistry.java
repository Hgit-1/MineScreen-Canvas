package dev.minescreen.client.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;

import dev.minescreen.ScreenGroup;
import dev.minescreen.client.content.ClientScreenProfile;
import dev.minescreen.client.content.ScreenContentSession;
import dev.minescreen.client.video.ExternalVideoPlaybackSession;
import dev.minescreen.client.video.VideoSource;
import dev.minescreen.client.web.BrowserSession;
import dev.minescreen.client.web.ExternalBrowserSession;

/** Ordered compatibility chain. Native implementations are referenced by class-name strings only. */
public final class ContentBackendRegistry {
    private static final List<BrowserBackendFactory> BROWSERS = List.of(
            new ReflectiveMcefFactory(), new ExternalChromiumFactory());
    private static final List<VideoBackendFactory> VIDEOS = List.of(
            new ReflectiveFfmpegFactory(), new SystemFfmpegFactory());

    private ContentBackendRegistry() {
    }

    public static BrowserSession createBrowser(ScreenGroup group, ClientScreenProfile profile,
            ScreenGroup stateGroup) {
        StringBuilder errors = new StringBuilder();
        BrowserRequest request = new BrowserRequest(group, profile, stateGroup);
        for (BrowserBackendFactory factory : BROWSERS) {
            CapabilityResult status = factory.probe();
            if (!status.available()) {
                append(errors, status.reason());
                continue;
            }
            try {
                return factory.create(request);
            } catch (Throwable failure) {
                Capability capability = status.backend() == BackendKind.MCEF
                        ? Capability.MCEF_BROWSER : Capability.EXTERNAL_BROWSER;
                CompatibilityManager.markFailed(capability, unwrap(failure));
                append(errors, message(unwrap(failure)));
            }
        }
        throw new IllegalStateException("No WEB backend is available: " + errors);
    }

    public static ScreenContentSession createVideo(ScreenGroup group, ClientScreenProfile profile,
            VideoSource source) {
        StringBuilder errors = new StringBuilder();
        VideoRequest request = new VideoRequest(group, profile, source);
        for (VideoBackendFactory factory : VIDEOS) {
            CapabilityResult status = factory.probe();
            if (!status.available()) {
                append(errors, status.reason());
                continue;
            }
            try {
                return factory.create(request);
            } catch (Throwable failure) {
                Capability capability = status.backend() == BackendKind.JAVACPP_FFMPEG
                        ? Capability.EMBEDDED_FFMPEG : Capability.EXTERNAL_FFMPEG;
                CompatibilityManager.markFailed(capability, unwrap(failure));
                append(errors, message(unwrap(failure)));
            }
        }
        throw new IllegalStateException("No VIDEO backend is available: " + errors);
    }

    private static final class ReflectiveMcefFactory implements BrowserBackendFactory {
        @Override public CapabilityResult probe() {
            return CompatibilityManager.probe(Capability.MCEF_BROWSER);
        }

        @Override
        public BrowserSession create(BrowserRequest request) {
            try {
                Class<?> type = Class.forName("dev.minescreen.client.web.McefBrowserSession", true,
                        ContentBackendRegistry.class.getClassLoader());
                Constructor<?> constructor = type.getConstructor(ScreenGroup.class,
                        ClientScreenProfile.class, boolean.class, ScreenGroup.class);
                return (BrowserSession) constructor.newInstance(request.group(), request.profile(),
                        true, request.stateGroup());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("MCEF backend could not be created", unwrap(exception));
            }
        }
    }

    private static final class ExternalChromiumFactory implements BrowserBackendFactory {
        @Override public CapabilityResult probe() {
            return CompatibilityManager.probe(Capability.EXTERNAL_BROWSER);
        }

        @Override
        public BrowserSession create(BrowserRequest request) {
            Path executable = CompatibilityManager.externalBrowser().orElseThrow();
            return new ExternalBrowserSession(request.group(), request.profile(), executable);
        }
    }

    private static final class ReflectiveFfmpegFactory implements VideoBackendFactory {
        @Override public CapabilityResult probe() {
            return CompatibilityManager.probe(Capability.EMBEDDED_FFMPEG);
        }

        @Override
        public ScreenContentSession create(VideoRequest request) {
            try {
                Class<?> type = Class.forName("dev.minescreen.client.video.VideoPlaybackSession", true,
                        ContentBackendRegistry.class.getClassLoader());
                Constructor<?> constructor = type.getConstructor(ScreenGroup.class,
                        ClientScreenProfile.class, VideoSource.class);
                return (ScreenContentSession) constructor.newInstance(request.group(),
                        request.profile(), request.source());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Embedded FFmpeg backend could not be created",
                        unwrap(exception));
            }
        }
    }

    private static final class SystemFfmpegFactory implements VideoBackendFactory {
        @Override public CapabilityResult probe() {
            return CompatibilityManager.probe(Capability.EXTERNAL_FFMPEG);
        }

        @Override
        public ScreenContentSession create(VideoRequest request) {
            return new ExternalVideoPlaybackSession(request.group(), request.profile(),
                    request.source(), CompatibilityManager.externalFfmpeg().orElseThrow(),
                    CompatibilityManager.externalFfprobe().orElseThrow());
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return failure.getCause() == null ? failure : failure.getCause();
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.isBlank()) return;
        if (!target.isEmpty()) target.append("; ");
        target.append(value);
    }
}
