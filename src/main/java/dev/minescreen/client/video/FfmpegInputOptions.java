package dev.minescreen.client.video;

import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.javacpp.Pointer;

import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;

/** Shared, bounded network options for the separate video and audio FFmpeg inputs. */
public final class FfmpegInputOptions {
    private FfmpegInputOptions() {
    }

    public static AVDictionary create(VideoSource source) {
        if (source.kind() == VideoSource.Kind.LOCAL_FILE) {
            return null;
        }
        AVDictionary options = new AVDictionary((Pointer) null);
        // FFmpeg values are microseconds. Reconnect keeps short CDN/network interruptions from
        // permanently ending a session; the AVIO interrupt callback still makes close immediate.
        av_dict_set(options, "rw_timeout", "15000000", 0);
        av_dict_set(options, "reconnect", "1", 0);
        av_dict_set(options, "reconnect_streamed", "1", 0);
        av_dict_set(options, "reconnect_on_network_error", "1", 0);
        av_dict_set(options, "reconnect_delay_max", "5", 0);
        av_dict_set(options, "user_agent", "MineScreen/0.3.0", 0);
        return options;
    }

    public static void free(AVDictionary options) {
        if (options != null) {
            av_dict_free(options);
        }
    }
}
