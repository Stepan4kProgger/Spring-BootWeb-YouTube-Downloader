package com.example.ytdlp.service;

import com.example.ytdlp.utils.constants.RegexPatterns;
import com.example.ytdlp.utils.model.DownloadProgress;
import com.example.ytdlp.utils.model.DownloadRequest;
import com.example.ytdlp.utils.model.DownloadResponse;
import com.example.ytdlp.config.ApplicationConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class YtDlpService {

    @Value("${yt-dlp.path}")
    private String ytDlpPath;

    @Value("${ffmpeg.path}")
    private String ffmpegPath;

    @Value("${app.download.directory:D:/downloads}")
    private String defaultDownloadDirectory;

    @Autowired
    private ApplicationConfig appConfig;

    private static final String HISTORY_FILE = "download_history.json";

    private static final Set<String> COMPATIBILITY_MODE_QUALITIES = Set.of(
            "144", "144p", "240", "240p", "360", "360p",
            "480", "480p", "720", "720p", "1080", "1080p"
    );

    private static final Set<String> BEST_MODE_QUALITIES = Set.of(
            "best", "max", "2160", "2160p", "1440", "1440p", "2k", "4k"
    );

    private final Map<String, DownloadProgress> activeDownloads = new ConcurrentHashMap<>();
    private final List<DownloadProgress> downloadHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        appConfig.loadConfig();

        if (appConfig.isClearHistoryOnStartup()) {
            clearDownloadHistory();
            log.info("Download history cleared on startup as configured");
        } else {
            loadDownloadHistory();
        }
    }

    // Метод для загрузки истории из файла
    private void loadDownloadHistory() {
        Path historyPath = Paths.get(HISTORY_FILE);

        if (!Files.exists(historyPath)) {
            log.info("No download history file found, creating empty history");
            saveDownloadHistory();
            return;
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            List<DownloadProgress> loadedHistory = objectMapper.readValue(
                    historyPath.toFile(),
                    new TypeReference<>() {}
            );

            downloadHistory.clear();
            downloadHistory.addAll(loadedHistory);
            log.info("Loaded {} items from download history", downloadHistory.size());

        } catch (Exception e) {
            log.error("Error loading download history from JSON format. Creating empty history.", e);
            downloadHistory.clear();
            saveDownloadHistory();
        }
    }

    private void saveDownloadHistory() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

            List<DownloadProgress> historyForSave = downloadHistory.stream()
                    .map(DownloadProgress::new)
                    .collect(Collectors.toList());

            String json = objectMapper.writeValueAsString(historyForSave);
            Path historyPath = Paths.get(HISTORY_FILE);
            Files.writeString(historyPath, json);

            log.info("Saved {} items to download history file: {}",
                    historyForSave.size(), historyPath.toAbsolutePath());

        } catch (IOException e) {
            log.error("Error saving download history to file: {}", e.getMessage(), e);
        }
    }

    // Метод для получения списка доступных дисков/папок
    public List<String> getAvailableDrives() {
        List<String> drives = new ArrayList<>();

        try {
            // Для Windows
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                File[] roots = File.listRoots();
                for (File root : roots) {
                    drives.add(root.getAbsolutePath());
                }
            } else {
                // Для Linux/Mac
                drives.add("/");
                drives.add(System.getProperty("user.home"));
                drives.add("/tmp");
                drives.add("/home");
            }
        } catch (Exception e) {
            log.error("Error getting drives: {}", e.getMessage());
            drives.add(System.getProperty("user.home"));
        }

        return drives;
    }

    // Метод для получения содержимого директории
    public List<DirectoryItem> listDirectory(String path) throws IOException {
        List<DirectoryItem> items = new ArrayList<>();
        File directory = new File(path);

        if (!directory.exists() || !directory.isDirectory()) {
            throw new IOException("Директория не существует: " + path);
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                DirectoryItem item = new DirectoryItem();
                item.setName(file.getName());
                item.setPath(file.getAbsolutePath());
                item.setDirectory(file.isDirectory());
                item.setSize(file.isFile() ? formatFileSize(file.length()) : "");
                item.setLastModified(new Date(file.lastModified()));

                items.add(item);
            }
        }

        // Сортируем: сначала папки, потом файлы
        items.sort((a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        return items;
    }

    @Data
    public static class DirectoryItem {
        private String name;
        private String path;
        private boolean isDirectory;
        private String size;
        private Date lastModified;
    }

    @Data
    private static class FormatSelection {
        final VideoFormatInfo videoFormat;
        final VideoFormatInfo audioFormat;
        final String videoUrl;
        final String audioUrl;
        final String videoFormatId;
        final String audioFormatId;
        final boolean isCombined;
    }

    @Data
    public static class VideoFormatInfo {
        private String format_id;
        private String ext;
        private String vcodec;
        private String acodec;
        private Integer height;
        private Integer width;
        private String format;
        private String format_note;
        private Integer abr;
        private String url;
        private String protocol;
    }

    @Data
    public static class VideoInfo {
        private String title;
        private String id;
        private List<VideoFormatInfo> formats;
        // добавьте другие поля по необходимости
    }

    public DownloadResponse downloadVideo(DownloadRequest request) {
        String downloadId = UUID.randomUUID().toString();
        DownloadProgress progress = new DownloadProgress();
        progress.setDownloadId(downloadId);
        progress.setUrl(request.getUrl());
        progress.setStatus("analyzing");
        progress.setProgress(0);
        progress.setStartTime(LocalDateTime.now());

        // Берем директорию из конфига, если не указана в запросе
        if (request.getDownloadDirectory() == null || request.getDownloadDirectory().trim().isEmpty()) {
            request.setDownloadDirectory(appConfig.getDirectory());
        }
        progress.setDownloadDirectory(request.getDownloadDirectory());

        // Берем качество из конфига, если не указано в запросе
        if (request.getFormat() == null || request.getFormat().trim().isEmpty()) {
            request.setFormat(appConfig.getQuality());
        }

        activeDownloads.put(downloadId, progress);

        log.info("=== Starting Download ===");
        log.info("Download ID: {}", downloadId);
        log.info("URL: {}", request.getUrl());
        log.info("Directory: {}", request.getDownloadDirectory());
        log.info("Requested quality: {}", request.getFormat());

        Path videoTempFile = null;
        Path audioTempFile = null;
        Path combinedTempFile = null;
        Path cookiesFile = null;
        Path finalOutputFile = null;
        FormatSelection formats = null;

        try {
            String targetDirectory = request.getDownloadDirectory();

            // Проверяем существование yt-dlp.exe и ffmpeg.exe
            if (!Files.exists(Paths.get(ytDlpPath))) {
                throw new IOException("yt-dlp.exe not found at: " + ytDlpPath);
            }
            if (!Files.exists(Paths.get(ffmpegPath))) {
                throw new IOException("ffmpeg.exe not found at: " + ffmpegPath);
            }

            // Создаем директорию для загрузок, если не существует
            Path downloadDir = Paths.get(targetDirectory);
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir);
                log.info("Created download directory: {}", downloadDir);
            }

            // Шаг 1: Получаем информацию о видео
            progress.setStatus("analyzing");
            progress.setProgress(10);
            VideoInfo videoInfo = getVideoInfo(request);
            formats = selectCompatibleFormats(videoInfo, request.getFormat());
            validateSelectedFormats(videoInfo, formats);

            // Создаем файл куки если нужно
            cookiesFile = createCookiesFile(request);

            // Генерируем финальное имя файла
            String finalFilename = generateFinalFilenameFromVideoInfo(videoInfo);
            finalOutputFile = downloadDir.resolve(finalFilename);

            // ПРОВЕРКА: Если финальный файл уже существует, добавляем суффикс
            if (Files.exists(finalOutputFile)) {
                String baseName = finalFilename.substring(0, finalFilename.lastIndexOf('.'));
                String extension = finalFilename.substring(finalFilename.lastIndexOf('.'));
                finalFilename = baseName + "_" + System.currentTimeMillis() + extension;
                finalOutputFile = downloadDir.resolve(finalFilename);
                log.info("File already exists, using new name: {}", finalFilename);
            }

            if (formats.isCombined()) {
                // КОМБИНИРОВАННЫЙ ФОРМАТ: скачиваем готовый MP4
                log.info("Using COMBINED format download");
                progress.setStatus("downloading_combined");
                progress.setProgress(20);

                // Создаем временный файл
                String tempPrefix = "temp_" + downloadId + "_";
                combinedTempFile = Files.createTempFile(downloadDir, tempPrefix + "combined", ".mp4");

                // Скачиваем комбинированный формат
                downloadFormat(request.getUrl(), formats.getVideoFormatId(), formats.getVideoUrl(),
                        combinedTempFile.toString(), cookiesFile, progress, 20, 90);

                // Перемещаем временный файл в финальный
                Files.move(combinedTempFile, finalOutputFile, StandardCopyOption.REPLACE_EXISTING);

            } else {
                // РАЗДЕЛЬНЫЕ ФОРМАТЫ: видео + аудио отдельно
                log.info("Using SEPARATE formats download");

                // Создаем временные файлы с уникальными именами
                String tempPrefix = "temp_" + downloadId + "_";
                videoTempFile = Files.createTempFile(downloadDir, tempPrefix + "video", ".mp4");
                audioTempFile = Files.createTempFile(downloadDir, tempPrefix + "audio", ".m4a");

                // Шаг 2: Скачиваем видео
                progress.setStatus("downloading_video");
                progress.setProgress(30);
                downloadFormat(request.getUrl(), formats.getVideoFormatId(), formats.getVideoUrl(),
                        videoTempFile.toString(), cookiesFile, progress, 30, 60);

                // Шаг 3: Скачиваем аудио
                progress.setStatus("downloading_audio");
                progress.setProgress(60);
                downloadFormat(request.getUrl(), formats.getAudioFormatId(), formats.getAudioUrl(),
                        audioTempFile.toString(), cookiesFile, progress, 60, 90);

                // Шаг 4: Объединяем через FFmpeg
                progress.setStatus("merging");
                progress.setProgress(90);
                mergeWithFfmpeg(videoTempFile.toString(), audioTempFile.toString(),
                        finalOutputFile.toString());
            }

            // Устанавливаем результат
            progress.setStatus("completed");
            progress.setProgress(100);
            progress.setFilename(finalFilename);
            progress.setEndTime(LocalDateTime.now());

            log.info("Download completed successfully. Final filename: {}", finalFilename);

            // Добавляем в историю
            addToDownloadHistory(progress);
            activeDownloads.remove(downloadId);

            return new DownloadResponse(true, "Download completed successfully",
                    targetDirectory, finalFilename, null);

        } catch (Exception e) {
            log.error("Download failed: {}", e.getMessage(), e);
            if (formats != null) {
                log.error("Failed formats - Video: {}, Audio: {}",
                        formats.getVideoFormatId(), formats.getAudioFormatId());
            }

            // ДОПОЛНИТЕЛЬНАЯ ОЧИСТКА при ошибке
            try {
                Path downloadDir = Paths.get(request.getDownloadDirectory());
                cleanupEmptyTempFiles(downloadDir, downloadId);
            } catch (Exception cleanupEx) {
                log.warn("Additional cleanup failed: {}", cleanupEx.getMessage());
            }

            return handleError(downloadId, progress, e, "Download process error");
        } finally {
        // Очищаем временные файлы в зависимости от типа загрузки
        try {
            if (formats != null && formats.isCombined()) {
                cleanupTempFiles(combinedTempFile, cookiesFile);
            } else {
                cleanupTempFiles(videoTempFile, audioTempFile, cookiesFile);
            }

            // ДОПОЛНИТЕЛЬНО: очищаем любые оставшиеся пустые временные файлы
            String targetDirectory = request.getDownloadDirectory();
            if (targetDirectory != null) {
                Path downloadDirPath = Paths.get(targetDirectory);
                cleanupEmptyTempFiles(downloadDirPath, downloadId);
            }

        } catch (Exception e) {
            log.warn("Error during temp file cleanup: {}", e.getMessage());
        }

        // Убедимся, что процесс удален из активных
        activeProcesses.remove(downloadId);
    }
    }

    // Метод для получения информации о видео
    private VideoInfo getVideoInfo(DownloadRequest request) throws IOException, InterruptedException {
        List<String> command = buildVideoInfoCommand(request);
        ProcessBuilder processBuilder = new ProcessBuilder(command);

        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();

        Process process = processBuilder.start();

        // Читаем stdout
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        // Читаем stderr для диагностики
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            log.error("Video info command failed with exit code: {}", exitCode);
            log.error("Error output: {}", errorOutput.toString());
            throw new IOException("Failed to get video info, exit code: " + exitCode + ". Error: " + errorOutput.toString());
        }

        // Проверяем, что вывод не пустой
        if (output.length() == 0) {
            throw new IOException("Video info command returned empty output");
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            VideoInfo videoInfo = objectMapper.readValue(output.toString(), VideoInfo.class);

            // Логируем основную информацию о видео
            log.info("Video info retrieved: '{}' (ID: {}), {} formats available",
                    videoInfo.getTitle(), videoInfo.getId(),
                    videoInfo.getFormats() != null ? videoInfo.getFormats().size() : 0);

            return videoInfo;
        } catch (Exception e) {
            log.error("Failed to parse video info JSON: {}", output.toString());
            throw new IOException("Failed to parse video info JSON: " + e.getMessage());
        }
    }

    // Команда для получения информации о видео
    private List<String> buildVideoInfoCommand(DownloadRequest request) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add(request.getUrl());

        // Добавляем куки если есть
        if (request.getCookies() != null && !request.getCookies().trim().isEmpty()) {
            Path cookiesFile = Files.createTempFile("cookies_info_" + System.currentTimeMillis(), ".txt");
            String formattedCookies = formatCookiesForNetscape(request.getCookies());
            Files.writeString(cookiesFile, formattedCookies, StandardCharsets.UTF_8);
            cookiesFile.toFile().deleteOnExit();

            command.add("--cookies");
            command.add(cookiesFile.toString());
        }

        command.add("--dump-json");
        command.add("--no-playlist");

        // ДОБАВИТЬ ЭТИ ПАРАМЕТРЫ ДЛЯ НАДЕЖНОСТИ
        command.add("--no-check-certificate");
        command.add("--prefer-insecure");
        command.add("--force-ipv4");
        command.add("--socket-timeout");
        command.add("30");

        log.info("Video info command: {}", String.join(" ", command));
        return command;
    }

    private FormatSelection selectCompatibleFormats(VideoInfo videoInfo, String requestedQuality) {
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

    // Обновленный парсер качества
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
            switch (quality) {
                case "best": case "max":
                    return null; // null означает "лучшее доступное" без ограничений

                case "2160p": case "2160": case "4k":
                    return 2160;
                case "1440p": case "1440": case "2k":
                    return 1440;
                case "1080p": case "1080":
                    return compatibilityMode ? 1080 : 1080;
                case "720p": case "720":
                    return 720;
                case "480p": case "480":
                    return 480;
                case "360p": case "360":
                    return 360;
                case "240p": case "240":
                    return 240;
                case "144p": case "144":
                    return 144;
                case "worst":
                    return compatibilityMode ? 144 : 144;
                default:
                    return compatibilityMode ? 1080 : null;
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid quality format: {}, using default", requestedQuality);
            return compatibilityMode ? 1080 : null;
        }
    }

    // Режим максимального качества - без ограничений по совместимости
    private FormatSelection findMaximumQualityFormats(List<VideoFormatInfo> formats, Integer targetHeight) {
        log.info("=== MAXIMUM QUALITY MODE ===");

        // Логируем все доступные форматы для анализа
        formats.forEach(f -> {
            if (f.getHeight() != null && f.getHeight() >= 1440) {
                log.info("High quality format: {} - {}x{} - {} (vcodec: {}, acodec: {})",
                        f.getFormat_id(), f.getWidth(), f.getHeight(),
                        f.getFormat_note(), f.getVcodec(), f.getAcodec());
            }
        });

        // 1. Сначала ищем комбинированные форматы (видео+аудио в одном файле)
        List<VideoFormatInfo> combinedFormats = formats.stream()
                .filter(f -> {
                    // Должен содержать и видео и аудио
                    String vcodec = f.getVcodec();
                    String acodec = f.getAcodec();
                    return vcodec != null && !"none".equals(vcodec) &&
                            acodec != null && !"none".equals(acodec);
                })
                .filter(f -> {
                    String protocol = f.getProtocol();
                    return protocol != null && "https".equals(protocol);
                })
                .collect(Collectors.toList());

        // Фильтруем по целевому качеству если указано
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
            VideoFormatInfo bestFormat = combinedFormats.get(0);
            log.info("Selected MAX QUALITY COMBINED format: {} - {}x{}",
                    bestFormat.getFormat_id(), bestFormat.getWidth(), bestFormat.getHeight());

            return new FormatSelection(bestFormat, null,
                    bestFormat.getUrl(), null,
                    bestFormat.getFormat_id(), null, true);
        }

        // 2. Если комбинированных нет, ищем раздельные форматы с максимальным качеством
        log.info("No combined formats found, looking for separate video/audio formats");

        // Ищем видео форматы с максимальным качеством
        List<VideoFormatInfo> videoFormats = formats.stream()
                .filter(f -> {
                    String vcodec = f.getVcodec();
                    return vcodec != null && !"none".equals(vcodec);
                })
                .filter(f -> {
                    String protocol = f.getProtocol();
                    return protocol != null && "https".equals(protocol);
                })
                .collect(Collectors.toList());

        // Фильтруем видео по целевому качеству
        if (targetHeight != null && !videoFormats.isEmpty()) {
            videoFormats = videoFormats.stream()
                    .filter(f -> {
                        Integer height = f.getHeight();
                        return height != null && height <= targetHeight;
                    })
                    .collect(Collectors.toList());
        }

        // Сортируем видео по качеству
        videoFormats.sort((f1, f2) -> {
            Integer h1 = f1.getHeight();
            Integer h2 = f2.getHeight();
            h1 = h1 != null ? h1 : 0;
            h2 = h2 != null ? h2 : 0;
            return h2.compareTo(h1);
        });

        // Ищем аудио форматы с максимальным качеством
        List<VideoFormatInfo> audioFormats = formats.stream()
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
                    return protocol != null && "https".equals(protocol);
                })
                .collect(Collectors.toList());

        // Сортируем аудио по битрейту
        audioFormats.sort((f1, f2) -> {
            Integer abr1 = f1.getAbr();
            Integer abr2 = f2.getAbr();
            abr1 = abr1 != null ? abr1 : 0;
            abr2 = abr2 != null ? abr2 : 0;
            return abr2.compareTo(abr1);
        });

        if (videoFormats.isEmpty()) {
            throw new RuntimeException("No video format found for maximum quality");
        }
        if (audioFormats.isEmpty()) {
            throw new RuntimeException("No audio format found for maximum quality");
        }

        VideoFormatInfo videoFormat = videoFormats.get(0);
        VideoFormatInfo audioFormat = audioFormats.get(0);

        log.info("Selected MAX QUALITY SEPARATE formats - Video: {}x{} ({}), Audio: {}kbps ({})",
                videoFormat.getWidth(), videoFormat.getHeight(), videoFormat.getFormat_id(),
                audioFormat.getAbr(), audioFormat.getFormat_id());

        return new FormatSelection(videoFormat, audioFormat,
                videoFormat.getUrl(), audioFormat.getUrl(),
                videoFormat.getFormat_id(), audioFormat.getFormat_id(), false);
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
            return height <= 1080; // 1080p и ниже - совместимый режим
        }

        // По умолчанию - совместимый режим
        return true;
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
            VideoFormatInfo bestFormat = combinedFormats.get(0);
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
                })
                .collect(Collectors.toList());

        // Сортируем аудио по битрейту (лучшее первое)
        audioFormats.sort((f1, f2) -> {
            Integer abr1 = f1.getAbr();
            Integer abr2 = f2.getAbr();
            abr1 = abr1 != null ? abr1 : 0;
            abr2 = abr2 != null ? abr2 : 0;
            return abr2.compareTo(abr1);
        });

        if (videoFormats.isEmpty()) {
            throw new RuntimeException("No compatible video format (H.264, MP4, ≤1080p) found");
        }
        if (audioFormats.isEmpty()) {
            throw new RuntimeException("No compatible audio format (AAC, M4A) found");
        }

        VideoFormatInfo videoFormat = videoFormats.get(0);
        VideoFormatInfo audioFormat = audioFormats.get(0);

        log.info("SELECTED SEPARATE FORMATS - Video: {}x{} ({}), Audio: {}kbps ({})",
                videoFormat.getWidth(), videoFormat.getHeight(), videoFormat.getFormat_id(),
                audioFormat.getAbr(), audioFormat.getFormat_id());

        return new FormatSelection(videoFormat, audioFormat,
                videoFormat.getUrl(), audioFormat.getUrl(),
                videoFormat.getFormat_id(), audioFormat.getFormat_id(), false);
    }

    // Скачивание отдельного формата
    private void downloadFormat(String url, String formatId, String formatUrl,
                                String outputFile, Path cookiesFile,
                                DownloadProgress progress, int startProgress, int endProgress)
            throws IOException, InterruptedException {

        // ПРОВЕРКА: Если файл уже существует и он пустой, удаляем его
        File output = new File(outputFile);
        if (output.exists() && output.length() == 0) {
            log.warn("Found empty existing file, deleting: {}", outputFile);
            Files.deleteIfExists(Paths.get(outputFile));
        }

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add(url);
        command.add("-f");
        command.add(formatId);
        command.add("--no-playlist");
        command.add("-o");
        command.add(outputFile);

        // МИНИМАЛЬНЫЙ НАБОР ПАРАМЕТРОВ:
        command.add("--no-overwrites");   // Не перезаписывать существующие файлы
        command.add("--continue");        // Продолжать частично скачанные файлы

        // Добавить retry логику
        command.add("--retries");
        command.add("10");

        if (cookiesFile != null) {
            command.add("--cookies");
            command.add(cookiesFile.toString());
        }

        log.info("Download command: {}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();
        activeProcesses.put(progress.getDownloadId(), process);

        // Чтение вывода для прогресса
        Thread progressThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("Download output: {}", line);
                    if (line.contains("[download]")) {
                        int currentProgress = parseProgressFromLine(line);
                        if (currentProgress > 0) {
                            // Масштабируем прогресс в диапазон [startProgress, endProgress]
                            int scaledProgress = startProgress + (currentProgress * (endProgress - startProgress)) / 100;
                            progress.setProgress(Math.min(scaledProgress, endProgress));
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Error reading download progress: {}", e.getMessage());
            }
        });
        progressThread.start();

        // ТАКЖЕ читаем stderr для диагностики ошибок
        Thread errorThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.warn("Download error: {}", line);
                    if (line.contains("ERROR") || line.contains("error")) {
                        log.error("Critical download error: {}", line);
                    }
                }
            } catch (IOException e) {
                log.error("Error reading download errors: {}", e.getMessage());
            }
        });
        errorThread.start();

        int exitCode = process.waitFor();
        progressThread.join();
        errorThread.join();
        activeProcesses.remove(progress.getDownloadId());

        // ПРОВЕРКА: Убедимся, что файл действительно скачан и не пустой
        File downloadedFile = new File(outputFile);
        if (!downloadedFile.exists()) {
            throw new IOException("Downloaded file does not exist: " + outputFile);
        }

        if (downloadedFile.length() == 0) {
            // Файл пустой - удаляем его и бросаем исключение
            Files.deleteIfExists(Paths.get(outputFile));
            throw new IOException("Downloaded file is empty (0 bytes): " + outputFile);
        }

        log.info("Download completed successfully: {} (size: {} bytes)",
                outputFile, downloadedFile.length());

        if (exitCode != 0) {
            throw new IOException("Download failed with exit code: " + exitCode);
        }
    }

    private void validateFormatAvailability(VideoInfo videoInfo, FormatSelection formats) throws IOException {
        log.info("Validating format availability...");

        // Проверяем видео формат
        if (formats.getVideoFormat() != null) {
            VideoFormatInfo videoFormat = formats.getVideoFormat();
            if (videoFormat.getUrl() == null || videoFormat.getUrl().trim().isEmpty()) {
                throw new IOException("Video format " + videoFormat.getFormat_id() + " has no URL");
            }

            // Проверяем, что формат действительно доступен в списке
            boolean formatExists = videoInfo.getFormats().stream()
                    .anyMatch(f -> f.getFormat_id().equals(videoFormat.getFormat_id())
                            && f.getUrl() != null && !f.getUrl().trim().isEmpty());

            if (!formatExists) {
                throw new IOException("Video format " + videoFormat.getFormat_id() + " is not available");
            }

            log.info("Video format {} validated: {}", videoFormat.getFormat_id(), videoFormat.getFormat_note());
        }

        // Проверяем аудио формат
        if (formats.getAudioFormat() != null) {
            VideoFormatInfo audioFormat = formats.getAudioFormat();
            if (audioFormat.getUrl() == null || audioFormat.getUrl().trim().isEmpty()) {
                throw new IOException("Audio format " + audioFormat.getFormat_id() + " has no URL");
            }

            boolean formatExists = videoInfo.getFormats().stream()
                    .anyMatch(f -> f.getFormat_id().equals(audioFormat.getFormat_id())
                            && f.getUrl() != null && !f.getUrl().trim().isEmpty());

            if (!formatExists) {
                throw new IOException("Audio format " + audioFormat.getFormat_id() + " is not available");
            }

            log.info("Audio format {} validated: {}", audioFormat.getFormat_id(), audioFormat.getFormat_note());
        }
    }

    // Объединение через FFmpeg
    private void mergeWithFfmpeg(String videoFile, String audioFile, String outputFile)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(videoFile);
        command.add("-i");
        command.add(audioFile);
        command.add("-c:v");
        command.add("copy"); // Копируем видео поток без изменений
        command.add("-c:a");
        command.add("copy"); // Копируем аудио поток без изменений
        command.add("-y"); // Перезаписываем выходной файл
        command.add(outputFile);

        log.info("Merging with FFmpeg (copy streams): {} + {} -> {}", videoFile, audioFile, outputFile);
        log.info("FFmpeg command: {}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();

        // Читаем вывод FFmpeg для диагностики
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("error") || line.contains("Error") || line.contains("ERROR")) {
                        log.error("FFmpeg error: {}", line);
                    } else {
                        log.debug("FFmpeg: {}", line);
                    }
                }
            } catch (IOException e) {
                log.error("Error reading FFmpeg output: {}", e.getMessage());
            }
        });
        outputReader.start();

        int exitCode = process.waitFor();
        outputReader.join();

        if (exitCode != 0) {
            throw new IOException("FFmpeg merge failed with exit code: " + exitCode);
        }

        log.info("FFmpeg merge completed successfully - streams copied without re-encoding");
    }

    // Генерация финального имени файла
    private String generateFinalFilenameFromVideoInfo(VideoInfo videoInfo) {
        String title = videoInfo.getTitle();
        if (title == null) {
            title = "video_" + System.currentTimeMillis();
        }
        title = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        return title + ".mp4";
    }

    // Создание файла куки
    private Path createCookiesFile(DownloadRequest request) throws IOException {
        if (request.getCookies() == null || request.getCookies().trim().isEmpty()) {
            return null;
        }

        Path cookiesFile = Files.createTempFile("cookies_download_" + System.currentTimeMillis(), ".txt");
        String formattedCookies = formatCookiesForNetscape(request.getCookies());
        Files.writeString(cookiesFile, formattedCookies, StandardCharsets.UTF_8);
        cookiesFile.toFile().deleteOnExit();

        log.info("Created cookies file with {} bytes", Files.size(cookiesFile));
        return cookiesFile;
    }

    // Очистка временных файлов
    private void cleanupTempFiles(Path... files) {
        for (Path file : files) {
            if (file != null) {
                try {
                    // Проверяем, существует ли файл и не пустой ли он
                    if (Files.exists(file)) {
                        long fileSize = Files.size(file);
                        if (fileSize == 0) {
                            log.info("Deleting empty temp file: {}", file);
                        } else {
                            log.debug("Deleting temp file: {} (size: {} bytes)", file, fileSize);
                        }
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete temp file: {}", file, e);
                }
            }
        }
    }

    // Новый метод для очистки пустых временных файлов
    private void cleanupEmptyTempFiles(Path downloadDir, String downloadId) {
        try {
            String tempPrefix = "temp_" + downloadId + "_";
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(downloadDir, tempPrefix + "*")) {
                for (Path tempFile : stream) {
                    if (Files.size(tempFile) == 0) {
                        Files.deleteIfExists(tempFile);
                        log.info("Deleted empty temp file: {}", tempFile);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not cleanup empty temp files: {}", e.getMessage());
        }
    }

    private String formatCookiesForNetscape(String rawCookies) {
        StringBuilder formatted = new StringBuilder();

        // Добавляем заголовок Netscape
        formatted.append("# Netscape HTTP Cookie File\n");
        formatted.append("# This file was generated by YT-DLP Spring Boot App\n");
        formatted.append("# Please edit at your own risk!\n\n");

        // Парсим сырые куки
        String[] cookiePairs = rawCookies.split(";");
        int validCookies = 0;

        for (String cookiePair : cookiePairs) {
            cookiePair = cookiePair.trim();
            if (cookiePair.isEmpty()) continue;

            String[] parts = cookiePair.split("=", 2);
            if (parts.length == 2) {
                String name = parts[0].trim();
                String value = parts[1].trim();

                if (!name.isEmpty() && !value.isEmpty()) {
                    // Форматируем в Netscape format
                    // domain \t flag \t path \t secure \t expiration \t name \t value
                    formatted.append(".youtube.com")  // domain
                            .append("\tTRUE")         // flag
                            .append("\t/")            // path
                            .append("\tTRUE")         // secure (TRUE для HTTPS сайтов)
                            .append("\t")             // expiration
                            .append(String.valueOf(System.currentTimeMillis() / 1000 + 86400)) // 1 день
                            .append("\t")
                            .append(name)
                            .append("\t")
                            .append(value)
                            .append("\n");
                    validCookies++;
                    log.debug("Formatted cookie: {} (value length: {})", name, value.length());
                }
            }
        }

        log.info("Successfully formatted {} cookies for Netscape format", validCookies);
        return formatted.toString();
    }

    private void validateSelectedFormats(VideoInfo videoInfo, FormatSelection formats) throws IOException {
        log.info("Validating selected formats...");

        if (formats.isCombined()) {
            // Проверяем комбинированный формат
            VideoFormatInfo format = formats.getVideoFormat();
            if (format.getUrl() == null || format.getUrl().trim().isEmpty()) {
                throw new IOException("Combined format " + format.getFormat_id() + " has no URL");
            }
            log.info("Validated combined format: {} - {}", format.getFormat_id(), format.getFormat_note());
        } else {
            // Проверяем видео формат
            VideoFormatInfo videoFormat = formats.getVideoFormat();
            if (videoFormat.getUrl() == null || videoFormat.getUrl().trim().isEmpty()) {
                throw new IOException("Video format " + videoFormat.getFormat_id() + " has no URL");
            }

            // Проверяем аудио формат
            VideoFormatInfo audioFormat = formats.getAudioFormat();
            if (audioFormat.getUrl() == null || audioFormat.getUrl().trim().isEmpty()) {
                throw new IOException("Audio format " + audioFormat.getFormat_id() + " has no URL");
            }

            log.info("Validated separate formats - Video: {}, Audio: {}",
                    videoFormat.getFormat_id(), audioFormat.getFormat_id());
        }
    }

    public String getYtDlpVersion() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(ytDlpPath, "--version");
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String version = reader.readLine();
                return version != null ? version : "Неизвестная версия";
            }
        } catch (IOException e) {
            log.error("Error getting yt-dlp version: {}", e.getMessage(), e);
            return "Ошибка загрузки: " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error getting version: {}", e.getMessage(), e);
            return "Неизвестная версия";
        }
    }

    // Метод получения версии ffmpeg
    public String getFfmpegVersion() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(ffmpegPath, "-version");
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String versionLine = reader.readLine();
                if (versionLine != null && versionLine.contains("ffmpeg version")) {
                    return versionLine.replace("ffmpeg version", "").trim().split(" ")[0];
                }
                return versionLine != null ? versionLine : "Неизвестная версия";
            }
        } catch (IOException e) {
            log.error("Error getting ffmpeg version: {}", e.getMessage(), e);
            return "Ошибка загрузки: " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error getting ffmpeg version: {}", e.getMessage(), e);
            return "Неизвестная версия";
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // Вспомогательные методы для парсинга
    private int parseProgressFromLine(String line) {
        // Пример: [download]  12.5% of 12.34MiB at 1.23MiB/s ETA 00:10
        java.util.regex.Matcher matcher = RegexPatterns.PROGRESS_PATTERN.matcher(line);
        if (matcher.find()) {
            return (int) Float.parseFloat(matcher.group(1));
        }
        return -1;
    }

    private String extractCleanFilename(String fullPath) {
        if (fullPath == null || fullPath.isEmpty()) {
            return "unknown";
        }

        try {
            // Декодируем строку для обработки кириллицы
            String decodedPath = URLDecoder.decode(fullPath, StandardCharsets.UTF_8);

            // Извлекаем только имя файла из полного пути
            File file = new File(decodedPath);
            String filename = file.getName();

            // Если имя файла выглядит корректно
            if (filename.contains(".") && filename.length() > 3) {
                return filename;
            }

            // Альтернативный метод извлечения имени файла
            String[] pathParts = decodedPath.replace("\\\\", "/").replace("\\", "/").split("/");
            if (pathParts.length > 0) {
                String lastPart = pathParts[pathParts.length - 1];
                if (lastPart.contains(".") && lastPart.length() > 3) {
                    return lastPart;
                }
            }

            return "unknown";
        } catch (Exception e) {
            log.error("Error extracting clean filename from: {}", fullPath, e);
            return "unknown";
        }
    }

    private String extractFilename(String text, String patternType) {
        if (text == null || text.isEmpty()) {
            return "unknown";
        }

        try {
            Pattern pattern;
            int groupIndex = switch (patternType) {
                case "destination" -> {
                    // Паттерн: [download] Destination: filename.ext
                    pattern = RegexPatterns.DESTINATION_PATTERN;
                    yield 1;
                }
                case "already_downloaded" -> {
                    // Паттерн для уже скачанных файлов (Windows и Unix пути)
                    pattern = RegexPatterns.ALREADY_DOWNLOADED_PATTERN;
                    yield 1;
                }
                case "merger" -> {
                    // Паттерн для строки [Merger]
                    pattern = RegexPatterns.MERGER_PATTERN;
                    yield 1;
                }
                default -> {
                    // Общий паттерн для любых строк с расширениями файлов
                    pattern = RegexPatterns.GENERAL_FILE_PATTERN;
                    yield 1;
                }
            };

            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String matchedText = matcher.group(groupIndex);
                return extractCleanFilename(matchedText);
            }
        } catch (Exception e) {
            log.error("Error parsing {} pattern from text: {}", patternType, text, e);
        }
        return "unknown";
    }

    private String extractFilenameFromOutput(String output) {
        log.info("Parsing complete output for filename");
        log.info("Full output: {}", output);

        String[] lines = output.split("\n");

        // В первую очередь ищем финальное имя файла из строки [Merger]
        for (String line : lines) {
            if (line.contains("[Merger]") && line.contains("Merging formats into")) {
                // Извлекаем путь из строки типа: [Merger] Merging formats into "D:\Work\Java\output\Solar System Model From a Drone's View.mp4"
                Pattern mergerPattern = Pattern.compile("Merging formats into \"([^\"]+)\"");
                Matcher matcher = mergerPattern.matcher(line);
                if (matcher.find()) {
                    String fullPath = matcher.group(1);
                    String filename = extractCleanFilename(fullPath);
                    log.info("Found final filename from Merger: {}", filename);
                    return filename;
                }
            }
        }

        // Если не нашли в [Merger], ищем другие паттерны
        for (String line : lines) {
            // Ищем строки типа: [download] D:\downloads\Full File Name.mp4 has already been downloaded
            if (line.contains("has already been downloaded") && line.contains("[download]")) {
                String filename = extractFilename(line, "already_downloaded");
                if (!filename.equals("unknown")) {
                    log.info("Found filename in 'already downloaded' line: {}", filename);
                    return filename;
                }
            }

            // Ищем строку Destination в выводе
            if (line.contains("Destination:")) {
                String filename = extractFilename(line, "destination");
                if (!filename.equals("unknown")) {
                    log.info("Found filename in Destination line: {}", filename);
                    // Не возвращаем сразу, так как это может быть временный файл
                }
            }
        }

        // Если всё ещё не нашли, ищем любые упоминания файлов с расширениями
        Matcher matcher = RegexPatterns.WINDOWS_PATH_PATTERN.matcher(output);
        if (matcher.find()) {
            String fullPath = matcher.group(1);
            String filename = extractCleanFilename(fullPath);
            log.info("Found filename via full path regex: {}", filename);
            return filename;
        }

        log.warn("Could not find filename in output. Output: {}", output);
        return "unknown";
    }

    public List<DownloadProgress> getActiveDownloads() {
        return new ArrayList<>(activeDownloads.values());
    }

    public void clearDownloadHistory() {
        downloadHistory.clear();
        saveDownloadHistory();
        log.info("Download history cleared");
    }

    public List<DownloadProgress> getDownloadHistory() {
        return downloadHistory.stream()
                .sorted((a, b) -> {
                    LocalDateTime aTime = a.getEndTime() != null ? a.getEndTime() : a.getStartTime();
                    LocalDateTime bTime = b.getEndTime() != null ? b.getEndTime() : b.getStartTime();

                    if (aTime == null) return 1;
                    if (bTime == null) return -1;
                    return bTime.compareTo(aTime); // новые выше старых
                })
                .collect(Collectors.toList());
    }

    public boolean pauseDownload(String downloadId) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null && progress.getProcess() != null &&
                "downloading".equals(progress.getStatus())) {
            try {
                Process process = progress.getProcess();
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                }

                progress.setStatus("paused");
                progress.setPausable(false);
                progress.setCancellable(true);

                activeProcesses.remove(downloadId);

                log.info("Download paused: {}", downloadId);
                return true;
            } catch (Exception e) {
                log.error("Error pausing download: {}", e.getMessage());
            }
        }
        return false;
    }

    public boolean resumeDownload(String downloadId) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null && "paused".equals(progress.getStatus())) {
            try {
                // Создаем новый запрос с теми же параметрами
                DownloadRequest request = new DownloadRequest();
                request.setUrl(progress.getUrl());
                request.setDownloadDirectory(progress.getDownloadDirectory());
                request.setFormat(appConfig.getQuality()); // Используем текущее качество из конфига

                // Запускаем новую загрузку - yt-dlp автоматически продолжит с того же места
                downloadVideo(request);

                // Удаляем старую paused запись из активных загрузок
                activeDownloads.remove(downloadId);

                log.info("Download resumed: {}", downloadId);
                return true;
            } catch (Exception e) {
                log.error("Error resuming download: {}", e.getMessage());
                return false;
            }
        }
        return false;
    }

    public boolean cancelDownload(String downloadId) {
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress != null && progress.getProcess() != null &&
                ("downloading".equals(progress.getStatus()) || "paused".equals(progress.getStatus()))) {
            try {
                // Останавливаем процесс
                Process process = progress.getProcess();
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                }

                // Пытаемся найти и удалить недокачанный файл
                deletePartialFile(progress);

                progress.setStatus("cancelled");
                progress.setCancellable(false);
                progress.setPausable(false);
                progress.setEndTime(LocalDateTime.now());
                progress.setErrorMessage("Загрузка отменена пользователем");

                // Удаляем из активных процессов
                activeProcesses.remove(downloadId);

                // Сохраняем в историю
                addToDownloadHistory(progress);

                // Удаляем из активных загрузок
                activeDownloads.remove(downloadId);

                log.info("Download cancelled and partial file deleted: {}", downloadId);
                return true;
            } catch (Exception e) {
                log.error("Error cancelling download: {}", e.getMessage());
            }
        }
        return false;
    }

    // Новый метод для удаления частично скачанного файла
    private void deletePartialFile(DownloadProgress progress) {
        try {
            if (progress.getFilename() != null && !progress.getFilename().equals("unknown")) {
                Path filePath = Paths.get(progress.getDownloadDirectory(), progress.getFilename());

                // Пытаемся удалить основной файл
                Files.deleteIfExists(filePath);

                // Также пытаемся удалить временные файлы yt-dlp (обычно с расширением .part)
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                        Paths.get(progress.getDownloadDirectory()),
                        progress.getFilename() + ".*")) {

                    for (Path tempFile : stream) {
                        if (tempFile.toString().endsWith(".part") ||
                                tempFile.toString().contains(progress.getFilename() + ".")) {
                            Files.deleteIfExists(tempFile);
                            log.info("Deleted partial file: {}", tempFile);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not delete partial file for download: {}", progress.getDownloadId(), e);
        }
    }

    public boolean deleteDownloadedFile(String downloadId) {
        // Ищем в активных загрузках
        DownloadProgress progress = activeDownloads.get(downloadId);
        if (progress == null) {
            // Ищем в истории
            progress = downloadHistory.stream()
                    .filter(p -> downloadId.equals(p.getDownloadId()))
                    .findFirst()
                    .orElse(null);
        }

        if (progress != null && ("completed".equals(progress.getStatus()) ||
                "error".equals(progress.getStatus()) ||
                "cancelled".equals(progress.getStatus()))) {
            try {
                Path filePath = Paths.get(progress.getDownloadDirectory(), progress.getFilename());
                Files.deleteIfExists(filePath);

                // Удаляем из истории
                downloadHistory.removeIf(p -> downloadId.equals(p.getDownloadId()));
                saveDownloadHistory();

                // Удаляем из активных, если там остался
                activeDownloads.remove(downloadId);

                log.info("File deleted: {}", filePath);
                return true;
            } catch (IOException e) {
                log.error("Error deleting file: {}", e.getMessage());
                return false;
            }
        }
        return false;
    }

    public Map<String, String> getActiveProcessesInfo() {
        Map<String, String> info = new HashMap<>();
        for (Map.Entry<String, Process> entry : activeProcesses.entrySet()) {
            Process process = entry.getValue();
            info.put(entry.getKey(),
                    process != null ?
                            "Alive: " + process.isAlive() :
                            "Null process");
        }
        return info;
    }

    public void stopAllDownloads() {
        log.info("Stopping all active downloads...");

        // 1. Останавливаем все процессы yt-dlp
        stopAllProcesses();

        // 2. Обновляем и сохраняем статусы всех активных загрузок
        updateActiveDownloadsStatus();

        log.info("All downloads stopped successfully");
    }

    private void stopAllProcesses() {
        int stoppedCount = 0;
        for (Map.Entry<String, Process> entry : activeProcesses.entrySet()) {
            String downloadId = entry.getKey();
            Process process = entry.getValue();

            try {
                if (process != null && process.isAlive()) {
                    log.info("Stopping process for download: {}", downloadId);
                    process.destroy();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        forceStopProcess(process);
                    }
                    stoppedCount++;
                }
            } catch (Exception e) {
                log.error("Error stopping process for download {}: {}", downloadId, e.getMessage());
                forceStopProcess(process);
            }
        }
        activeProcesses.clear();
        log.info("Stopped {} processes", stoppedCount);
    }

    private void forceStopProcess(Process process) {
        try {
            process.destroyForcibly();
        } catch (Exception e) {
            log.error("Failed to force destroy process: {}", e.getMessage());
        }
    }

    private void updateActiveDownloadsStatus() {
        for (DownloadProgress progress : activeDownloads.values()) {
            if ("downloading".equals(progress.getStatus()) || "paused".equals(progress.getStatus())) {
                progress.setStatus("cancelled");
                progress.setCancellable(false);
                progress.setPausable(false);
                progress.setEndTime(LocalDateTime.now());
                progress.setErrorMessage("Загрузка прервана при завершении приложения");

                addToDownloadHistory(progress);
            }
        }
        activeDownloads.clear();
    }

    private void addToDownloadHistory(DownloadProgress progress) {
        downloadHistory.add(progress);
        saveDownloadHistory();
    }

    private DownloadResponse handleError(String downloadId, DownloadProgress progress,
                                         Exception e, String context) {
        log.error("Error {}: {}", context, e.getMessage(), e);

        if (progress != null) {
            progress.setStatus("error");
            progress.setErrorMessage(context + ": " + e.getMessage());
            progress.setEndTime(LocalDateTime.now());

            addToDownloadHistory(progress);
            activeDownloads.remove(downloadId);
            activeProcesses.remove(downloadId);
        }

        return new DownloadResponse(false, null, null, "unknown",
                context + ": " + e.getMessage());
    }

    public void openFileInExplorer(String filename, String directory) throws IOException {
        Path filePath = Paths.get(directory, filename);
        if (!Files.exists(filePath)) {
            throw new IOException("Файл не существует: " + filePath);
        }

        String os = System.getProperty("os.name").toLowerCase();
        String[] command;

        if (os.contains("win")) {
            command = new String[]{"explorer", "/select,", filePath.toString()};
        } else if (os.contains("mac")) {
            command = new String[]{"open", "-R", filePath.toString()};
        } else if (os.contains("nix") || os.contains("nux")) {
            command = new String[]{"xdg-open", filePath.getParent().toString()};
        } else {
            throw new IOException("Неподдерживаемая ОС: " + os);
        }

        Runtime.getRuntime().exec(command);
    }
}