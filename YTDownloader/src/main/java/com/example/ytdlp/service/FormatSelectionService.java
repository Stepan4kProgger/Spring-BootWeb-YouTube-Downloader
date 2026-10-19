package com.example.ytdlp.service;

import com.example.ytdlp.utils.model.FormatSelection;
import com.example.ytdlp.utils.model.VideoFormatInfo;
import com.example.ytdlp.utils.model.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
class FormatSelectionService {

    public FormatSelection selectFormats(VideoInfo videoInfo, String requestedQuality,
                                         boolean compatibilityMode) {
        List<VideoFormatInfo> formats = videoInfo.getFormats();
        Integer targetHeight = parseRequestedQuality(requestedQuality, compatibilityMode);

        VideoFormatInfo combined = findBestVideo(formats, compatibilityMode, targetHeight, true);
        VideoFormatInfo video    = findBestVideo(formats, compatibilityMode, targetHeight, false);
        VideoFormatInfo audio    = findBestAudio(formats, compatibilityMode);

        if (preferSeparate(combined, video, audio, compatibilityMode, targetHeight)) {
            return buildSeparate(video, audio);
        }
        if (combined != null) return buildCombined(combined);
        if (video != null && audio != null) return buildSeparate(video, audio);
        throw new RuntimeException("No suitable formats found");
    }

    /**
     * @param requireAudio true — ищем комбинированный (video+audio) формат,
     *                     false — только видео-дорожку без встроенного звука.
     */
    private VideoFormatInfo findBestVideo(List<VideoFormatInfo> formats, boolean compat,
                                          Integer targetHeight, boolean requireAudio) {
        return formats.stream()
                .filter(f -> {
                    String vcodec = f.getVcodec();
                    if (vcodec == null || "none".equals(vcodec)) return false;

                    String acodec = f.getAcodec();
                    boolean hasAudio = acodec != null && !"none".equals(acodec);
                    if (hasAudio != requireAudio) return false;
                    if (!isHttp(f)) return false;

                    if (compat) {
                        if (!"mp4".equals(f.getExt())) return false;
                        if (!vcodec.contains("avc1") && !vcodec.contains("h264")) return false;
                        if (requireAudio && !acodec.contains("mp4a") && !acodec.contains("aac")) return false;
                        int maxH = targetHeight != null ? Math.min(targetHeight, 1080) : 1080;
                        return f.getHeight() != null && f.getHeight() <= maxH;
                    }
                    return targetHeight == null
                            || (f.getHeight() != null && f.getHeight() <= targetHeight);
                })
                .max(Comparator.comparingInt(this::heightOf))
                .orElse(null);
    }

    private VideoFormatInfo findBestAudio(List<VideoFormatInfo> formats, boolean compat) {
        return formats.stream()
                .filter(f -> {
                    String acodec = f.getAcodec();
                    if (acodec == null || "none".equals(acodec)) return false;

                    String vcodec = f.getVcodec();
                    if (vcodec != null && !"none".equals(vcodec)) return false;
                    if (!isHttp(f)) return false;

                    if (compat) {
                        if (!"m4a".equals(f.getExt())) return false;
                        return acodec.contains("mp4a") || acodec.contains("aac");
                    }
                    return true;
                })
                .min((a, b) -> {
                    boolean oa = isOriginalAudio(a);
                    boolean ob = isOriginalAudio(b);
                    if (oa != ob) return oa ? -1 : 1;

                    if (!compat) {
                        int pa = getAudioContainerPriority(a.getExt());
                        int pb = getAudioContainerPriority(b.getExt());
                        if (pa != pb) return Integer.compare(pb, pa);
                    }
                    return Double.compare(abrOf(b), abrOf(a));
                })
                .orElse(null);
    }

    private boolean preferSeparate(VideoFormatInfo combined, VideoFormatInfo video,
                                   VideoFormatInfo audio, boolean compat, Integer targetHeight) {
        if (compat || video == null || audio == null) return false;
        if (combined == null) return true;

        if (targetHeight != null) {
            return heightOf(video) == targetHeight && heightOf(combined) < targetHeight;
        }
        return heightOf(video) > heightOf(combined);
    }

    private boolean isHttp(VideoFormatInfo f) {
        String p = f.getProtocol();
        return "https".equals(p) || "http".equals(p);
    }

    private int heightOf(VideoFormatInfo f) {
        return f.getHeight() != null ? f.getHeight() : 0;
    }

    private double abrOf(VideoFormatInfo f) {
        return f.getAbr() != null ? f.getAbr() : 0.0;
    }

    /**
     * Оригинальная дорожка: language_preference >= 10 или "original" в format_note.
     */
    private boolean isOriginalAudio(VideoFormatInfo f) {
        Integer pref = f.getLanguage_preference();
        if (pref != null && pref >= 10) return true;
        String note = f.getFormat_note();
        return note != null && note.toLowerCase().contains("original");
    }

    private int getAudioContainerPriority(String container) {
        if (container == null) return 0;
        return switch (container.toLowerCase()) {
            case "m4a"  -> 3;
            case "mp4"  -> 2;
            case "webm" -> 1;
            default     -> 0;
        };
    }

    private FormatSelection buildCombined(VideoFormatInfo f) {
        return new FormatSelection(f, null, f.getUrl(), null, f.getFormat_id(), null, true);
    }

    private FormatSelection buildSeparate(VideoFormatInfo video, VideoFormatInfo audio) {
        return new FormatSelection(video, audio, video.getUrl(), audio.getUrl(),
                video.getFormat_id(), audio.getFormat_id(), false);
    }

    private Integer parseRequestedQuality(String requestedQuality, boolean compatibilityMode) {
        if (requestedQuality == null || requestedQuality.trim().isEmpty()) {
            return compatibilityMode ? 1080 : null;
        }
        String quality = requestedQuality.toLowerCase().trim();
        try {
            if (quality.matches("\\d+")) {
                int height = Integer.parseInt(quality);
                return compatibilityMode ? Math.min(height, 1080) : height;
            }
            return switch (quality) {
                case "best", "max"          -> null;
                case "2160p", "2160", "4k"  -> 2160;
                case "1440p", "1440", "2k"  -> 1440;
                case "1080p", "1080"        -> 1080;
                case "720p",  "720"         -> 720;
                case "480p",  "480"         -> 480;
                case "360p",  "360"         -> 360;
                case "240p",  "240"         -> 240;
                case "144p",  "144", "worst" -> 144;
                default -> compatibilityMode ? 1080 : null;
            };
        } catch (NumberFormatException e) {
            log.warn("Invalid quality format: {}", requestedQuality);
            return compatibilityMode ? 1080 : null;
        }
    }
}