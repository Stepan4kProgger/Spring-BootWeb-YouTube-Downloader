package com.example.ytdlp.service;

import com.example.ytdlp.utils.model.FormatSelection;
import com.example.ytdlp.utils.model.VideoFormatInfo;
import com.example.ytdlp.utils.model.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
class FormatSelectionService {
    private static final Set<String> COMPATIBILITY_MODE_QUALITIES = Set.of(
            "144", "144p", "240", "240p", "360", "360p",
            "480", "480p", "720", "720p", "1080", "1080p"
    );

    private static final Set<String> BEST_MODE_QUALITIES = Set.of(
            "best", "max", "2160", "2160p", "1440", "1440p", "2k", "4k"
    );

    public FormatSelection selectCompatibleFormats(VideoInfo videoInfo, String requestedQuality) {
        List<VideoFormatInfo> formats = videoInfo.getFormats();

        if (formats == null) {
            throw new RuntimeException("No formats found in video info");
        }

        // Логируем все доступные форматы для отладки
        log.info("=== AVAILABLE FORMATS ===");
        formats.forEach(f -> {
            if (f.getHeight() != null && f.getHeight() <= 1080) {
                log.info("Format: {} - {}x{} - {} (vcodec: {}, acodec: {}, ext: {})",
                        f.getFormat_id(), f.getWidth(), f.getHeight(),
                        f.getFormat_note(), f.getVcodec(), f.getAcodec(), f.getExt());
            }
        });

        // Определяем режим работы
        boolean compatibilityMode = isCompatibilityMode(requestedQuality);
        Integer targetHeight = parseRequestedQuality(requestedQuality, compatibilityMode);

        log.info("Using {} mode for quality: {} (target height: {})",
                compatibilityMode ? "COMPATIBILITY" : "MAXIMUM QUALITY",
                requestedQuality, targetHeight);

        if (compatibilityMode) {
            // СОВМЕСТИМЫЙ РЕЖИМ - гарантируем работу на приставке
            return findCompatibleFormats(formats, targetHeight);
        } else {
            // РЕЖИМ МАКСИМАЛЬНОГО КАЧЕСТВА - любой формат без ограничений
            return findMaximumQualityFormats(formats, targetHeight);
        }
    }

    // Метод для поиска раздельных совместимых форматов
    private FormatSelection findCompatibleFormats(List<VideoFormatInfo> formats, Integer targetHeight) {
        log.info("=== COMPATIBILITY MODE ===");
        log.info("Looking for formats compatible with set-top box (H.264/AAC, ≤1080p)");

        // Сначала ищем комбинированные форматы (видео+аудио в одном) с H.264 и AAC
        List<VideoFormatInfo> combinedFormats = formats.stream()
                .filter(f -> {
                    // Проверяем контейнер
                    String ext = f.getExt();
                    if (!"mp4".equals(ext)) return false;

                    // Проверяем видео кодек (H.264)
                    String vcodec = f.getVcodec();
                    if (vcodec == null || "none".equals(vcodec)) return false;
                    if (!vcodec.contains("avc1") && !vcodec.contains("h264")) return false;

                    // Проверяем аудио кодек (AAC)
                    String acodec = f.getAcodec();
                    if (acodec == null || "none".equals(acodec)) return false;
                    if (!acodec.contains("mp4a") && !acodec.contains("aac")) return false;

                    // Проверяем разрешение
                    Integer height = f.getHeight();
                    if (height == null || height > 1080) return false;

                    // Проверяем протокол
                    String protocol = f.getProtocol();
                    return "https".equals(protocol) || "http".equals(protocol);
                })
                .collect(Collectors.toList());

        // Фильтруем по целевому качеству
        if (targetHeight != null && !combinedFormats.isEmpty()) {
            combinedFormats = combinedFormats.stream()
                    .filter(f -> {
                        Integer height = f.getHeight();
                        return height != null && height <= targetHeight;
                    })
                    .collect(Collectors.toList());
        }

        // Сортируем комбинированные форматы по качеству (лучшее первое)
        combinedFormats.sort((f1, f2) -> {
            Integer h1 = f1.getHeight();
            Integer h2 = f2.getHeight();
            h1 = h1 != null ? h1 : 0;
            h2 = h2 != null ? h2 : 0;
            return h2.compareTo(h1);
        });

        if (!combinedFormats.isEmpty()) {
            VideoFormatInfo bestFormat = combinedFormats.getFirst();
            log.info("Selected COMBINED format: {} - {}x{} - {} (vcodec: {}, acodec: {})",
                    bestFormat.getFormat_id(), bestFormat.getWidth(), bestFormat.getHeight(),
                    bestFormat.getFormat_note(), bestFormat.getVcodec(), bestFormat.getAcodec());

            return new FormatSelection(bestFormat, null,
                    bestFormat.getUrl(), null,
                    bestFormat.getFormat_id(), null, true);
        }

        log.info("No combined formats found, looking for separate video/audio formats");

        // Ищем раздельные видео форматы с H.264
        List<VideoFormatInfo> videoFormats = formats.stream()
                .filter(f -> {
                    String ext = f.getExt();
                    if (!"mp4".equals(ext)) return false;

                    String vcodec = f.getVcodec();
                    if (vcodec == null || "none".equals(vcodec)) return false;
                    if (!vcodec.contains("avc1") && !vcodec.contains("h264")) return false;

                    String protocol = f.getProtocol();
                    if (!"https".equals(protocol) && !"http".equals(protocol)) return false;

                    Integer height = f.getHeight();
                    if (height == null || height > 1080) return false;

                    // Должен быть видео-only
                    String acodec = f.getAcodec();
                    return acodec == null || "none".equals(acodec);
                })
                .collect(Collectors.toList());

        // Фильтруем видео по качеству
        if (targetHeight != null && !videoFormats.isEmpty()) {
            videoFormats = videoFormats.stream()
                    .filter(f -> {
                        Integer height = f.getHeight();
                        return height != null && height <= targetHeight;
                    })
                    .collect(Collectors.toList());
        }

        // Сортируем видео по качеству (лучшее первое)
        videoFormats.sort((f1, f2) -> {
            Integer h1 = f1.getHeight();
            Integer h2 = f2.getHeight();
            h1 = h1 != null ? h1 : 0;
            h2 = h2 != null ? h2 : 0;
            return h2.compareTo(h1);
        });

        // Ищем аудио форматы с AAC в M4A контейнере
        List<VideoFormatInfo> audioFormats = formats.stream()
                .filter(f -> {
                    String ext = f.getExt();
                    if (!"m4a".equals(ext)) return false;

                    String acodec = f.getAcodec();
                    if (acodec == null) return false;
                    if (!acodec.contains("mp4a") && !acodec.contains("aac")) return false;

                    String protocol = f.getProtocol();
                    if (!"https".equals(protocol) && !"http".equals(protocol)) return false;

                    // Должен быть аудио-only
                    String vcodec = f.getVcodec();
                    return vcodec == null || "none".equals(vcodec);
                }).sorted((f1, f2) -> {
                    Integer abr1 = f1.getAbr();
                    Integer abr2 = f2.getAbr();
                    abr1 = abr1 != null ? abr1 : 0;
                    abr2 = abr2 != null ? abr2 : 0;
                    return abr2.compareTo(abr1);
                }).toList();

        // Сортируем аудио по битрейту (лучшее первое)

        if (videoFormats.isEmpty()) {
            throw new RuntimeException("No compatible video format (H.264, MP4, ≤1080p) found");
        }
        if (audioFormats.isEmpty()) {
            throw new RuntimeException("No compatible audio format (AAC, M4A) found");
        }

        VideoFormatInfo videoFormat = videoFormats.getFirst();
        VideoFormatInfo audioFormat = audioFormats.getFirst();

        log.info("SELECTED SEPARATE FORMATS - Video: {}x{} ({}), Audio: {}kbps ({})",
                videoFormat.getWidth(), videoFormat.getHeight(), videoFormat.getFormat_id(),
                audioFormat.getAbr(), audioFormat.getFormat_id());

        return new FormatSelection(videoFormat, audioFormat,
                videoFormat.getUrl(), audioFormat.getUrl(),
                videoFormat.getFormat_id(), audioFormat.getFormat_id(), false);
    }

    // Режим максимального качества - без ограничений по совместимости
    private FormatSelection findMaximumQualityFormats(List<VideoFormatInfo> formats, Integer targetHeight) {
        log.info("=== MAXIMUM QUALITY MODE ===");
        log.info("Target height: {}", targetHeight);

        // Логируем все доступные форматы для анализа
        formats.forEach(f -> {
            if (f.getHeight() != null && f.getHeight() >= 720) {
                log.info("High quality format: {} - {}x{} - {} (vcodec: {}, acodec: {})",
                        f.getFormat_id(), f.getWidth(), f.getHeight(),
                        f.getFormat_note(), f.getVcodec(), f.getAcodec());
            }
        });

        // 1. Если указано целевое качество - ищем точное соответствие или ближайшее НЕ ВЫШЕ целевого
        if (targetHeight != null) {
            log.info("Looking for EXACT or LOWER quality match: {}p", targetHeight);

            // Сначала ищем комбинированные форматы с точным соответствием или ближайшим НИЖЕ
            VideoFormatInfo exactCombined = findBestFormatByHeight(formats, targetHeight, true, true);
            VideoFormatInfo lowerCombined = findBestFormatByHeight(formats, targetHeight, true, false);

            VideoFormatInfo exactVideo = findBestFormatByHeight(formats, targetHeight, false, true);
            VideoFormatInfo lowerVideo = findBestFormatByHeight(formats, targetHeight, false, false);
            VideoFormatInfo bestAudio = findBestAudioFormat(formats);

            // Приоритет: точное соответствие > ближайшее ниже > ближайшее выше
            if (exactCombined != null) {
                log.info("Selected EXACT COMBINED format: {} - {}x{}",
                        exactCombined.getFormat_id(), exactCombined.getWidth(), exactCombined.getHeight());
                return new FormatSelection(exactCombined, null, exactCombined.getUrl(), null,
                        exactCombined.getFormat_id(), null, true);
            } else if (exactVideo != null && bestAudio != null) {
                log.info("Selected EXACT SEPARATE formats - Video: {}x{} ({}), Audio: {}kbps ({})",
                        exactVideo.getWidth(), exactVideo.getHeight(), exactVideo.getFormat_id(),
                        bestAudio.getAbr(), bestAudio.getFormat_id());
                return new FormatSelection(exactVideo, bestAudio, exactVideo.getUrl(), bestAudio.getUrl(),
                        exactVideo.getFormat_id(), bestAudio.getFormat_id(), false);
            } else if (lowerCombined != null) {
                log.info("Selected LOWER COMBINED format: {} - {}x{}",
                        lowerCombined.getFormat_id(), lowerCombined.getWidth(), lowerCombined.getHeight());
                return new FormatSelection(lowerCombined, null, lowerCombined.getUrl(), null,
                        lowerCombined.getFormat_id(), null, true);
            } else if (lowerVideo != null && bestAudio != null) {
                log.info("Selected LOWER SEPARATE formats - Video: {}x{} ({}), Audio: {}kbps ({})",
                        lowerVideo.getWidth(), lowerVideo.getHeight(), lowerVideo.getFormat_id(),
                        bestAudio.getAbr(), bestAudio.getFormat_id());
                return new FormatSelection(lowerVideo, bestAudio, lowerVideo.getUrl(), bestAudio.getUrl(),
                        lowerVideo.getFormat_id(), bestAudio.getFormat_id(), false);
            }

            log.warn("No format found for target height: {}p, falling back to maximum quality", targetHeight);
        }

        // 2. Если целевое качество не указано или не найдено - используем максимальное доступное качество
        log.info("Using MAXIMUM AVAILABLE quality");

        VideoFormatInfo bestCombined = null;
        VideoFormatInfo bestVideo = null;
        VideoFormatInfo bestAudio = findBestAudioFormat(formats);

        // Ищем лучший комбинированный формат
        List<VideoFormatInfo> combinedFormats = formats.stream()
                .filter(f -> {
                    String vcodec = f.getVcodec();
                    String acodec = f.getAcodec();
                    return vcodec != null && !"none".equals(vcodec) &&
                            acodec != null && !"none".equals(acodec);
                })
                .filter(f -> {
                    String protocol = f.getProtocol();
                    return "https".equals(protocol) || "http".equals(protocol);
                })
                .sorted((f1, f2) -> {
                    Integer h1 = f1.getHeight();
                    Integer h2 = f2.getHeight();
                    h1 = h1 != null ? h1 : 0;
                    h2 = h2 != null ? h2 : 0;
                    return h2.compareTo(h1);
                })
                .toList();

        if (!combinedFormats.isEmpty()) {
            bestCombined = combinedFormats.getFirst();
        }

        // Ищем лучшее видео
        List<VideoFormatInfo> videoFormats = formats.stream()
                .filter(f -> {
                    String vcodec = f.getVcodec();
                    return vcodec != null && !"none".equals(vcodec);
                })
                .filter(f -> {
                    String acodec = f.getAcodec();
                    return acodec == null || "none".equals(acodec);
                })
                .filter(f -> {
                    String protocol = f.getProtocol();
                    return "https".equals(protocol) || "http".equals(protocol);
                })
                .sorted((f1, f2) -> {
                    Integer h1 = f1.getHeight();
                    Integer h2 = f2.getHeight();
                    h1 = h1 != null ? h1 : 0;
                    h2 = h2 != null ? h2 : 0;
                    return h2.compareTo(h1);
                })
                .toList();

        if (!videoFormats.isEmpty()) {
            bestVideo = videoFormats.getFirst();
        }

        // Сравниваем и выбираем лучший вариант
        if (bestCombined != null && bestVideo != null) {
            int combinedHeight = bestCombined.getHeight() != null ? bestCombined.getHeight() : 0;
            int videoHeight = bestVideo.getHeight() != null ? bestVideo.getHeight() : 0;

            if (videoHeight > combinedHeight && bestAudio != null) {
                log.info("SELECTED SEPARATE FORMATS - Video: {}x{} ({}), Audio: {}kbps ({}) - BETTER QUALITY",
                        bestVideo.getWidth(), bestVideo.getHeight(), bestVideo.getFormat_id(),
                        bestAudio.getAbr(), bestAudio.getFormat_id());
                return new FormatSelection(bestVideo, bestAudio, bestVideo.getUrl(), bestAudio.getUrl(),
                        bestVideo.getFormat_id(), bestAudio.getFormat_id(), false);
            } else {
                log.info("SELECTED COMBINED FORMAT: {} - {}x{}",
                        bestCombined.getFormat_id(), bestCombined.getWidth(), bestCombined.getHeight());
                return new FormatSelection(bestCombined, null, bestCombined.getUrl(), null,
                        bestCombined.getFormat_id(), null, true);
            }
        } else if (bestCombined != null) {
            log.info("SELECTED COMBINED FORMAT: {} - {}x{}",
                    bestCombined.getFormat_id(), bestCombined.getWidth(), bestCombined.getHeight());
            return new FormatSelection(bestCombined, null, bestCombined.getUrl(), null,
                    bestCombined.getFormat_id(), null, true);
        } else if (bestVideo != null && bestAudio != null) {
            log.info("SELECTED SEPARATE FORMATS - Video: {}x{} ({}), Audio: {}kbps ({})",
                    bestVideo.getWidth(), bestVideo.getHeight(), bestVideo.getFormat_id(),
                    bestAudio.getAbr(), bestAudio.getFormat_id());
            return new FormatSelection(bestVideo, bestAudio, bestVideo.getUrl(), bestAudio.getUrl(),
                    bestVideo.getFormat_id(), bestAudio.getFormat_id(), false);
        }

        throw new RuntimeException("No suitable formats found for maximum quality mode");
    }

    // Вспомогательный метод для поиска формата по высоте
    private VideoFormatInfo findBestFormatByHeight(List<VideoFormatInfo> formats, int targetHeight,
                                                   boolean combined, boolean exactMatch) {
        // Фильтр по типу формата
        // Фильтр по протоколу
        // Фильтр по высоте
        // Сортируем: для exactMatch - любая сортировка, для не exact - по убыванию высоты
        return formats.stream()
                .filter(f -> {
                    // Фильтр по типу формата
                    if (combined) {
                        String vcodec = f.getVcodec();
                        String acodec = f.getAcodec();
                        if (!(vcodec != null && !"none".equals(vcodec) &&
                                acodec != null && !"none".equals(acodec))) {
                            return false;
                        }
                    } else {
                        String vcodec = f.getVcodec();
                        String acodec = f.getAcodec();
                        if (!(vcodec != null && !"none".equals(vcodec) &&
                                (acodec == null || "none".equals(acodec)))) {
                            return false;
                        }
                    }

                    // Фильтр по протоколу
                    String protocol = f.getProtocol();
                    if (!"https".equals(protocol) && !"http".equals(protocol)) {
                        return false;
                    }

                    // Фильтр по высоте
                    Integer height = f.getHeight();
                    if (height == null) return false;

                    if (exactMatch) {
                        return height == targetHeight;
                    } else {
                        return height <= targetHeight;
                    }
                }).min((f1, f2) -> {
                    // Сортируем: для exactMatch - любая сортировка, для не exact - по убыванию высоты
                    Integer h1 = f1.getHeight();
                    Integer h2 = f2.getHeight();
                    h1 = h1 != null ? h1 : 0;
                    h2 = h2 != null ? h2 : 0;
                    return exactMatch ? 0 : h2.compareTo(h1);
                })
                .orElse(null);
    }

    // Вспомогательный метод для поиска лучшего аудио
    private VideoFormatInfo findBestAudioFormat(List<VideoFormatInfo> formats) {
        // Предпочитаем форматы с контейнером, совместимым с MP4
        // Приоритет: M4A > MP4 > другие форматы
        // Затем по битрейту
        return formats.stream()
                .filter(f -> {
                    String acodec = f.getAcodec();
                    return acodec != null && !"none".equals(acodec);
                })
                .filter(f -> {
                    String vcodec = f.getVcodec();
                    return vcodec == null || "none".equals(vcodec);
                })
                .filter(f -> {
                    String protocol = f.getProtocol();
                    return "https".equals(protocol) || "http".equals(protocol);
                }).min((f1, f2) -> {
                    // Приоритет: M4A > MP4 > другие форматы
                    int priority1 = getAudioContainerPriority(f1.getExt());
                    int priority2 = getAudioContainerPriority(f2.getExt());
                    if (priority1 != priority2) {
                        return Integer.compare(priority2, priority1);
                    }

                    // Затем по битрейту
                    Integer abr1 = f1.getAbr();
                    Integer abr2 = f2.getAbr();
                    abr1 = abr1 != null ? abr1 : 0;
                    abr2 = abr2 != null ? abr2 : 0;
                    return abr2.compareTo(abr1);
                })
                .orElse(null);
    }

    // Приоритет контейнеров для аудио (чем выше число, тем лучше)
    private int getAudioContainerPriority(String container) {
        if (container == null) return 0;

        return switch (container.toLowerCase()) {
            case "m4a" -> 3;  // Лучший - совместим с MP4
            case "mp4" -> 2;  // Хороший - совместим с MP4
            case "webm" -> 1; // Средний - может быть проблемным
            default -> 0;     // Другие форматы
        };
    }

    // Определение режима работы
    private boolean isCompatibilityMode(String requestedQuality) {
        if (requestedQuality == null || requestedQuality.trim().isEmpty()) {
            return true; // По умолчанию - совместимый режим
        }

        String quality = requestedQuality.toLowerCase().trim();

        // Если качество явно указано как одно из "лучших" - используем режим максимального качества
        if (BEST_MODE_QUALITIES.contains(quality)) {
            return false;
        }

        // Если качество указано числом - проверяем диапазон
        if (quality.matches("\\d+")) {
            int height = Integer.parseInt(quality);
            // Для 480p и выше используем режим максимального качества
            return height < 480; // 360p и ниже - совместимый режим, 480p и выше - максимальное качество
        }

        // Для строковых обозначений
        return switch (quality) {
            case "480", "480p" -> false; // 480p использует режим максимального качества
            case "360", "360p", "240", "240p", "144", "144p", "worst" -> true;
            default -> true; // по умолчанию - совместимый режим
        };
    }

    private Integer parseRequestedQuality(String requestedQuality, boolean compatibilityMode) {
        if (requestedQuality == null || requestedQuality.trim().isEmpty()) {
            return compatibilityMode ? 1080 : null; // В совместимом режиме по умолчанию 1080p
        }

        String quality = requestedQuality.toLowerCase().trim();

        try {
            if (quality.matches("\\d+")) {
                int height = Integer.parseInt(quality);
                if (compatibilityMode) {
                    // В совместимом режиме ограничиваем 1080p
                    return Math.min(height, 1080);
                } else {
                    // В режиме максимального качества - любое число
                    return height;
                }
            }

            // Обработка строковых обозначений
            return switch (quality) {
                case "best", "max" -> null; // null означает "лучшее доступное" без ограничений

                case "2160p", "2160", "4k" -> 2160;
                case "1440p", "1440", "2k" -> 1440;
                case "1080p", "1080" -> 1080;
                case "720p", "720" -> 720;
                case "480p", "480" -> 480;
                case "360p", "360" -> 360;
                case "240p", "240" -> 240;
                case "144p", "144", "worst" -> 144;
                default -> compatibilityMode ? 1080 : null;
            };
        } catch (NumberFormatException e) {
            log.warn("Invalid quality format: {}, using default", requestedQuality);
            return compatibilityMode ? 1080 : null;
        }
    }

    public FormatSelection selectFormats(VideoInfo videoInfo, String requestedQuality, boolean compatibilityMode) {
        Integer targetHeight = parseRequestedQuality(requestedQuality, compatibilityMode);

        return compatibilityMode ?
                findCompatibleFormats(videoInfo.getFormats(), targetHeight) :
                findMaximumQualityFormats(videoInfo.getFormats(), targetHeight);
    }
}