package com.example.ytdlp.service;

import com.example.ytdlp.model.DownloadProgress;
import com.example.ytdlp.model.DownloadRequest;
import com.example.ytdlp.model.DownloadResponse;
import com.example.ytdlp.config.ApplicationConfig;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ExecutorService progressExecutor = Executors.newCachedThreadPool();

    @PostConstruct
    public void init() {
        loadDownloadHistory();
    }

    // Метод для загрузки истории из файла
    private void loadDownloadHistory() {
        try {
            Path historyPath = Paths.get(HISTORY_FILE);
            if (Files.exists(historyPath)) {
                List<String> lines = Files.readAllLines(historyPath);
                downloadHistory.clear();

                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        String[] parts = line.split("\\|");
                        if (parts.length >= 6) {
                            DownloadProgress progress = new DownloadProgress();
                            progress.setUrl(parts[0]);
                            progress.setFilename(parts[1]);
                            progress.setStatus(parts[2]);
                            progress.setProgress(Integer.parseInt(parts[3]));
                            progress.setStartTime(LocalDateTime.parse(parts[4]));
                            progress.setEndTime(LocalDateTime.parse(parts[5]));
                            progress.setDownloadDirectory(parts.length > 6 ? parts[6] : "");
                            progress.setErrorMessage(parts.length > 7 ? parts[7] : "");

                            downloadHistory.add(progress);
                        }
                    }
                }
                log.info("Loaded {} items from download history", downloadHistory.size());
            }
        } catch (IOException e) {
            log.error("Error loading download history", e);
        } catch (Exception e) {
            log.error("Error parsing download history", e);
        }
    }

    private void saveDownloadHistory() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

            String json = objectMapper.writeValueAsString(downloadHistory);
            Path historyPath = Paths.get(HISTORY_FILE);
            Files.write(historyPath, json.getBytes());

            log.info("Saved {} items to download history file: {}",
                    downloadHistory.size(), historyPath.toAbsolutePath());

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
                saveAppConfig();
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
            StringBuilder output = new StringBuilder();

            // Создаем отдельный поток для чтения вывода в реальном времени
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
                            String filename = null;
                            if (line.contains("Destination:")) {
                                filename = extractFilenameFromDestinationLine(line);
                            } else if (line.contains("has already been downloaded")) {
                                filename = extractFilenameFromAlreadyDownloadedLine(line);
                            } else {
                                filename = extractFilenameFromOtherLines(line);
                            }

                            if (filename != null && !filename.equals("unknown")) {
                                progress.setFilename(filename);
                                log.info("Detected filename in real-time: {}", filename);
                            }
                        }

                        // Также обрабатываем другие паттерны
                        if (line.contains("Writing") || line.contains("[ffmpeg]") || line.contains("[Merger]")) {
                            String filename = extractFilenameFromOtherLines(line);
                            if (filename != null && !filename.equals("unknown")) {
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

                // Сохраняем в историю
                downloadHistory.add(progress);
                saveDownloadHistory();

                return new DownloadResponse(true, "Download completed successfully",
                        targetDirectory, filename, null);
            } else {
                progress.setStatus("error");
                progress.setErrorMessage("Download failed with exit code: " + exitCode);
                progress.setEndTime(LocalDateTime.now());

                String filename = extractFilenameFromOutput(fullOutput);
                progress.setFilename(filename);

                log.error("Download failed. Exit code: {}, Output: {}", exitCode, fullOutput);

                // Сохраняем в историю даже при ошибке
                downloadHistory.add(progress);
                saveDownloadHistory();

                return new DownloadResponse(false, null, targetDirectory, filename,
                        "Download failed with exit code: " + exitCode + ". Output: " + fullOutput);
            }

        } catch (IOException | InterruptedException e) {
            progress.setStatus("error");
            progress.setErrorMessage("Error: " + e.getMessage());
            progress.setEndTime(LocalDateTime.now());

            log.error("Error during download: {}", e.getMessage(), e);

            // Сохраняем в историю при ошибке
            downloadHistory.add(progress);
            saveDownloadHistory();

            return new DownloadResponse(false, null, null, "unknown",
                    "Error: " + e.getMessage());
        } finally {
            // Удаляем из активных загрузок через некоторое время
            scheduleCleanup(downloadId);
        }
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

        if (Boolean.TRUE.equals(request.getExtractAudio())) {
            command.add("--extract-audio");
            if (request.getAudioFormat() != null) {
                command.add("--audio-format");
                command.add(request.getAudioFormat());
            }
        }

        if (request.getQuality() != null) {
            command.add("--audio-quality");
            command.add(request.getQuality() + "K");
        }

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

    private void saveAppConfig() {
        try {
            Properties props = new Properties();
            props.setProperty("app.download.directory", appConfig.getDirectory());
            props.setProperty("app.download.remember-last-directory",
                    String.valueOf(appConfig.isRememberLastDirectory()));

            Path configPath = Paths.get("application-custom.properties");
            try (OutputStream output = Files.newOutputStream(configPath)) {
                props.store(output, "Custom application properties");
            }
        } catch (IOException e) {
            log.error("Error saving app config: {}", e.getMessage(), e);
        }
    }

    // Вспомогательные методы для парсинга
    private int parseProgressFromLine(String line) {
        // Пример: [download]  12.5% of 12.34MiB at 1.23MiB/s ETA 00:10
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\\[download]\\s+(\\d+\\.?\\d*)%").matcher(line);
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

    private String extractFilenameFromDestinationLine(String line) {
        try {
            // Паттерн: [download] Destination: filename.ext
            String destinationPart = line.substring(line.indexOf("Destination:") + 12).trim();

            // Удаляем кавычки если есть
            if (destinationPart.startsWith("\"") && destinationPart.endsWith("\"")) {
                destinationPart = destinationPart.substring(1, destinationPart.length() - 1);
            }

            // Извлекаем только имя файла (последняя часть пути)
            return extractCleanFilename(destinationPart);
        } catch (Exception e) {
            log.error("Error parsing destination line: {}", e.getMessage());
            return "unknown";
        }
    }

    // Метод для парсинга других строк вывода
    private String extractFilenameFromOtherLines(String line) {
        try {
            // Ищем паттерны с расширениями файлов
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "([^\\s]+\\.(mp4|mp3|webm|m4a|ogg|wav|flv|avi|mov|wmv|mkv))").matcher(line);
            if (matcher.find()) {
                String filename = matcher.group(1);
                return extractCleanFilename(filename);
            }
        } catch (Exception e) {
            log.error("Error parsing other line: {}", e.getMessage());
        }
        return null;
    }

    // Улучшенный метод извлечения имени файла из вывода
    private String extractFilenameFromOutput(String output) {
        log.info("Parsing complete output for filename");
        log.info("Full output: {}", output);

        // Сначала ищем строку с полным путём к файлу (когда файл уже существует)
        String[] lines = output.split("\n");
        for (String line : lines) {
            // Ищем строки типа: [download] D:\downloads\Full File Name.mp4 has already been downloaded
            if (line.contains("has already been downloaded") && line.contains("[download]")) {
                String filename = extractFilenameFromAlreadyDownloadedLine(line);
                if (!filename.equals("unknown")) {
                    log.info("Found filename in 'already downloaded' line: {}", filename);
                    return filename;
                }
            }

            // Ищем строку Destination в выводе
            if (line.contains("Destination:")) {
                String filename = extractFilenameFromDestinationLine(line);
                if (!filename.equals("unknown")) {
                    log.info("Found filename in Destination line: {}", filename);
                    return filename;
                }
            }
        }

        // Если не нашли, ищем другие паттерны
        for (String line : lines) {
            if (line.contains("[download]") || line.contains("Writing") || line.contains("[ffmpeg]")) {
                String filename = extractFilenameFromOtherLines(line);
                if (filename != null && !filename.equals("unknown")) {
                    log.info("Found filename in other line: {}", filename);
                    return filename;
                }
            }
        }

        // Если всё ещё не нашли, ищем любые упоминания файлов с расширениями
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "([a-zA-Z]:\\\\[^\\n]+?\\.(mp4|mp3|webm|m4a|ogg|wav|flv|avi|mov|wmv|mkv))").matcher(output);
        if (matcher.find()) {
            String fullPath = matcher.group(1);
            String filename = extractCleanFilename(fullPath);
            log.info("Found filename via full path regex: {}", filename);
            return filename;
        }

        log.warn("Could not find filename in output. Output: {}", output);
        return "unknown";
    }

    // Добавим более специфичные регулярные выражения
    private String extractFilenameFromAlreadyDownloadedLine(String line) {
        try {
            // Более точное регулярное выражение для Windows путей
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                            "[a-zA-Z]:\\\\[^\\n]+?\\.(mp4|mp3|webm|m4a|ogg|wav|flv|avi|mov|wmv|mkv)")
                    .matcher(line);

            if (matcher.find()) {
                String fullPath = matcher.group();
                return extractCleanFilename(fullPath);
            }

            // Альтернативный вариант для Unix-подобных путей
            matcher = java.util.regex.Pattern.compile(
                            "/[^\\n]+?\\.(mp4|mp3|webm|m4a|ogg|wav|flv|avi|mov|wmv|mkv)")
                    .matcher(line);

            if (matcher.find()) {
                String fullPath = matcher.group();
                return extractCleanFilename(fullPath);
            }

            return "unknown";
        } catch (Exception e) {
            log.error("Error parsing 'already downloaded' line: {}", e.getMessage());
            return "unknown";
        }
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

    // Метод для получения активных загрузок
    public List<DownloadProgress> getActiveDownloads() {
        return new ArrayList<>(activeDownloads.values());
    }

    public void clearDownloadHistory() throws IOException {
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

    // Метод для открытия проводника с файлом
    public void openFileInExplorer(String filename, String directory) throws IOException {
        if (directory == null || directory.trim().isEmpty()) {
            throw new IOException("Директория не указана");
        }

        if (filename == null || filename.trim().isEmpty()) {
            throw new IOException("Имя файла не указано");
        }

        Path filePath = Paths.get(directory).resolve(filename);
        File file = filePath.toFile();

        log.info("Checking file existence: {}", file.getAbsolutePath());

        if (!file.exists()) {
            throw new IOException("Файл не существует: " + filePath);
        }

        // Команды для открытия проводника в разных ОС
        String os = System.getProperty("os.name").toLowerCase();

        try {
            if (os.contains("win")) {
                // Windows - открываем папку с выделенным файлом
                log.info("Opening Windows explorer for: {}", file.getAbsolutePath());
                Runtime.getRuntime().exec(new String[]{"explorer", "/select,", file.getAbsolutePath()});

            } else if (os.contains("mac")) {
                // Mac
                log.info("Opening Mac finder for: {}", file.getAbsolutePath());
                Runtime.getRuntime().exec(new String[]{"open", "-R", file.getAbsolutePath()});

            } else if (os.contains("nix") || os.contains("nux")) {
                // Linux - открываем папку
                log.info("Opening Linux file manager for: {}", file.getParent());
                Runtime.getRuntime().exec(new String[]{"xdg-open", file.getParent()});

            } else {
                throw new IOException("Неподдерживаемая операционная система: " + os);
            }

            log.info("Explorer process started successfully");

        } catch (IOException e) {
            log.error("Failed to start explorer process: {}", e.getMessage());
            throw new IOException("Не удалось запустить проводник: " + e.getMessage());
        }
    }
}