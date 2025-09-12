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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @Value("${app.download.directory:D:/downloads}")
    private String defaultDownloadDirectory;

    @Autowired
    private ApplicationConfig appConfig;

    private static final String HISTORY_FILE = "download_history.json";

    private final Map<String, DownloadProgress> activeDownloads = new ConcurrentHashMap<>();
    private final List<DownloadProgress> downloadHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Загружаем конфигурацию при старте
        appConfig.loadConfig();

        if (appConfig.isClearHistoryOnStartup()) {
            clearDownloadHistory();
            log.info("Download history cleared on startup as configured");
        } else {
            loadDownloadHistory();
        }
    }

    private boolean shouldClearHistoryOnStartup() {
        try {
            Path configPath = Paths.get("application-custom.properties");
            if (Files.exists(configPath)) {
                Properties props = new Properties();
                try (InputStream input = Files.newInputStream(configPath)) {
                    props.load(input);
                    return Boolean.parseBoolean(props.getProperty("app.download.clear-history-on-startup", "false"));
                }
            }
        } catch (IOException e) {
            log.error("Error reading clear history setting: {}", e.getMessage());
        }
        return false;
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

    public DownloadResponse downloadVideo(DownloadRequest request) {
        String downloadId = UUID.randomUUID().toString();
        DownloadProgress progress = new DownloadProgress();
        progress.setDownloadId(downloadId);
        progress.setUrl(request.getUrl());
        progress.setStatus("downloading");
        progress.setProgress(0);
        progress.setStartTime(LocalDateTime.now());
        progress.setDownloadDirectory(request.getDownloadDirectory());

        activeDownloads.put(downloadId, progress);
        log.info("Starting download: {} -> {}", request.getUrl(), request.getDownloadDirectory());

        try {
            String targetDirectory = request.getDownloadDirectory();

            // Если директория не указана, используем сохраненную
            if (targetDirectory == null || targetDirectory.trim().isEmpty()) {
                targetDirectory = appConfig.getDirectory();
            }

            // Сохраняем выбранную директорию в настройки
            if (appConfig.isRememberLastDirectory()) {
                appConfig.setDirectory(targetDirectory);
                saveDirectoryConfig(targetDirectory);
            }

            // Проверяем существование yt-dlp.exe
            Path ytDlp = Paths.get(ytDlpPath);
            if (!Files.exists(ytDlp)) {
                String errorMsg = "yt-dlp.exe not found at: " + ytDlpPath;
                log.error(errorMsg);
                progress.setStatus("error");
                progress.setErrorMessage(errorMsg);
                progress.setEndTime(LocalDateTime.now());
                return new DownloadResponse(false, null, null, errorMsg);
            }

            // Создаем директорию для загрузок, если не существует
            Path downloadDir = Paths.get(targetDirectory);
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir);
                log.info("Created download directory: {}", downloadDir);
            }

            // Формируем команду для выполнения
            List<String> command = buildCommand(request, ytDlpPath, targetDirectory);
            log.info("Executing command: {}", command);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.directory(downloadDir.toFile()); // Устанавливаем рабочую директорию

            Process process = processBuilder.start();
            progress.setProcess(process);
            activeProcesses.put(downloadId, process);

            StringBuilder output = new StringBuilder();

            // Создаем отдельный поток для чтения вывода в реальном времени
            Thread outputReaderThread = getOutputReaderThread(process, output, progress);

            // Ждем завершения процесса
            int exitCode = process.waitFor();
            outputReaderThread.join(); // Ждем завершения потока чтения

            String fullOutput = output.toString();
            log.info("yt-dlp process completed with exit code: {}", exitCode);
            log.debug("Full output: {}", fullOutput);

            if (exitCode == 0) {
                progress.setStatus("completed");
                progress.setProgress(100);
                progress.setEndTime(LocalDateTime.now());

                String filename = extractFilenameFromOutput(fullOutput);
                progress.setFilename(filename);

                log.info("Download completed successfully. Filename: {}", filename);

                addToDownloadHistory(progress);

                return new DownloadResponse(true, "Download completed successfully",
                        targetDirectory, filename, null);
            } else {
                progress.setStatus("error");
                progress.setErrorMessage("Download failed with exit code: " + exitCode);
                progress.setEndTime(LocalDateTime.now());

                String filename = extractFilenameFromOutput(fullOutput);
                progress.setFilename(filename);

                log.error("Download failed. Exit code: {}, Output: {}", exitCode, fullOutput);

                addToDownloadHistory(progress);

                return new DownloadResponse(false, null, targetDirectory, filename,
                        "Download failed with exit code: " + exitCode + ". Output: " + fullOutput);
            }

        } catch (IOException | InterruptedException e) {
            return handleError(downloadId, progress, e, "Download process error");
        } catch (Exception e) {
            return handleError(downloadId, progress, e, "Unexpected download error");
        } finally {
            scheduleCleanup(downloadId);
        }
    }

    private Thread getOutputReaderThread(Process process, StringBuilder output, DownloadProgress progress) {
        Thread outputReaderThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.info("yt-dlp: {}", line);

                    // Обрабатываем строки в реальном времени для прогресса
                    if (line.contains("[download]")) {
                        // Парсим прогресс
                        int currentProgress = parseProgressFromLine(line);
                        if (currentProgress > 0) {
                            progress.setProgress(currentProgress);
                            log.debug("Download progress: {}%", currentProgress);
                        }

                        // Обновляем имя файла в реальном времени
                        String filename;
                        if (line.contains("Destination:")) {
                            filename = extractFilename(line, "destination");
                        } else if (line.contains("has already been downloaded")) {
                            filename = extractFilename(line, "already_downloaded");
                        } else {
                            filename = extractFilename(line, "general");
                        }

                        if (!filename.equals("unknown")) {
                            progress.setFilename(filename);
                            log.info("Detected filename in real-time: {}", filename);
                        }
                    }

                    // Также обрабатываем другие паттерны
                    if (line.contains("Writing") || line.contains("[ffmpeg]") || line.contains("[Merger]")) {
                        String filename = extractFilename(line, "general");
                        if (!filename.equals("unknown")) {
                            progress.setFilename(filename);
                            log.info("Detected filename from other pattern: {}", filename);
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Error reading process output: {}", e.getMessage());
            }
        });

        outputReaderThread.start();
        return outputReaderThread;
    }

    private List<String> buildCommand(DownloadRequest request, String ytDlpPath, String targetDirectory) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add(request.getUrl());

        // Добавляем поддержку Unicode
        command.add("--encoding");
        command.add("UTF-8");

        // Добавляем опции
        if (request.getFormat() != null) {
            command.add("-f");
            command.add(request.getFormat());
        }

        command.add("-o");
        command.add(Paths.get(targetDirectory, "%(title)s.%(ext)s").toString());

        // Добавляем полезные флаги
        command.add("--no-playlist");
        command.add("--newline");

        return command;
    }

    public String getVersion() {
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

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private void saveDirectoryConfig(String directory) {
        if (appConfig.isRememberLastDirectory()) {
            appConfig.setDirectory(directory);
            appConfig.updateConfig("directory", directory);
        }
    }

    private void loadAppConfig() {
        try {
            Path configPath = Paths.get("application-custom.properties");
            if (Files.exists(configPath)) {
                Properties props = new Properties();
                try (InputStream input = Files.newInputStream(configPath)) {
                    props.load(input);

                    // Загружаем другие настройки если нужно
                    String directory = props.getProperty("app.download.directory");
                    if (directory != null) {
                        appConfig.setDirectory(directory);
                    }

                    String rememberLastDir = props.getProperty("app.download.remember-last-directory");
                    if (rememberLastDir != null) {
                        appConfig.setRememberLastDirectory(Boolean.parseBoolean(rememberLastDir));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error loading app config: {}", e.getMessage());
        }
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

        // Сначала ищем строку с полным путём к файлу (когда файл уже существует)
        String[] lines = output.split("\n");
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
                    return filename;
                }
            }
        }

        // Если не нашли, ищем другие паттерны
        for (String line : lines) {
            if (line.contains("[download]") || line.contains("Writing") || line.contains("[ffmpeg]")) {
                String filename = extractFilename(line, "general");
                if (!filename.equals("unknown")) {
                    log.info("Found filename in other line: {}", filename);
                    return filename;
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

    private void scheduleCleanup(String downloadId) {
        new Thread(() -> {
            try {
                Thread.sleep(300000); // Удаляем через 5 минут
                activeDownloads.remove(downloadId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
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
        // Исключаем активные загрузки из истории
        Set<String> activeUrls = getActiveDownloads().stream()
                .map(DownloadProgress::getUrl)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return downloadHistory.stream()
                .filter(item -> item.getUrl() == null || !activeUrls.contains(item.getUrl()))
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
                    process.destroy();
                    progress.setStatus("paused");
                    progress.setPausable(false);

                    // Удаляем из активных процессов
                    activeProcesses.remove(downloadId);

                    log.info("Download paused: {}", downloadId);
                    return true;
                }
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
                // Перезапускаем загрузку с того же URL
                DownloadRequest request = new DownloadRequest();
                request.setUrl(progress.getUrl());
                request.setDownloadDirectory(progress.getDownloadDirectory());

                // Запускаем новую загрузку
                downloadVideo(request);
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
                Process process = progress.getProcess();
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                progress.setStatus("cancelled");
                progress.setCancellable(false);
                progress.setPausable(false);
                progress.setEndTime(LocalDateTime.now());

                // Удаляем из активных процессов
                activeProcesses.remove(downloadId);

                // Сохраняем в историю
                addToDownloadHistory(progress);

                // Удаляем из активных загрузок
                activeDownloads.remove(downloadId);

                log.info("Download cancelled: {}", downloadId);
                return true;
            } catch (Exception e) {
                log.error("Error cancelling download: {}", e.getMessage());
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

        if (progress != null && "completed".equals(progress.getStatus())) {
            try {
                Path filePath = Paths.get(progress.getDownloadDirectory(), progress.getFilename());
                Files.deleteIfExists(filePath);

                // Удаляем из истории
                downloadHistory.removeIf(p -> downloadId.equals(p.getDownloadId()));
                saveDownloadHistory();

                log.info("File deleted: {}", filePath);
                return true;
            } catch (IOException e) {
                log.error("Error deleting file: {}", e.getMessage());
                return false;
            }
        }
        return false;
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