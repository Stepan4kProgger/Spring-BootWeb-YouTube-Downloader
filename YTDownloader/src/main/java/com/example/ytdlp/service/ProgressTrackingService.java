package com.example.ytdlp.service;

import com.example.ytdlp.config.ApplicationConfig;
import com.example.ytdlp.utils.constants.RegexPatterns;
import com.example.ytdlp.utils.model.DownloadProgress;
import com.example.ytdlp.utils.model.DownloadRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@Getter
@RequiredArgsConstructor
public class ProgressTrackingService {
    private final Map<String, DownloadProgress> activeDownloads = new ConcurrentHashMap<>();
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final HistoryService historyService; // для добавления в историю

    @Autowired private ApplicationConfig appConfig;

    public DownloadProgress initializeProgress(String downloadId, DownloadRequest request) {
        DownloadProgress progress = new DownloadProgress();
        progress.setDownloadId(downloadId);
        progress.setUrl(request.getUrl());
        progress.setStatus("analyzing");
        progress.setProgress(0);
        progress.setStartTime(LocalDateTime.now());
        progress.setDownloadDirectory(appConfig.getDirectory());

        activeDownloads.put(downloadId, progress);
        return progress;
    }

    public void updateProgress(String downloadId, String status, int progress) {
        DownloadProgress downloadProgress = activeDownloads.get(downloadId);
        if (downloadProgress != null) {
            downloadProgress.setStatus(status);
            downloadProgress.setProgress(progress);
        }
    }

    public DownloadProgress getProgress(String downloadId) {
        return activeDownloads.get(downloadId);
    }

    public void removeProgress(String downloadId) {
        activeDownloads.remove(downloadId);
    }

    int parseProgressFromLine(String line) {
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
}