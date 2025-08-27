package com.example.ytdlp.service;

import com.example.ytdlp.model.DownloadRequest;
import com.example.ytdlp.model.DownloadResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class YtDlpService {

    @Value("${yt-dlp.path}")
    private String ytDlpPath;

    public DownloadResponse downloadVideo(DownloadRequest request) {
        try {
            // Теперь downloadDirectory ОБЯЗАТЕЛЕН в запросе
            String targetDirectory = request.getDownloadDirectory();
            if (targetDirectory == null || targetDirectory.trim().isEmpty()) {
                return new DownloadResponse(false, null,
                        "Download directory is required", "No directory specified");
            }

            // Проверяем существование yt-dlp.exe
            Path ytDlp = Paths.get(ytDlpPath);
            if (!Files.exists(ytDlp)) {
                return new DownloadResponse(false, null,
                        "yt-dlp.exe not found at: " + ytDlpPath, "1");
            }

            // Создаем директорию для загрузок, если не существует
            Path downloadDir = Paths.get(targetDirectory);
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir);
            }

            // Формируем команду для выполнения
            List<String> command = buildCommand(request, ytDlpPath, targetDirectory);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.info("yt-dlp: {}", line);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                return new DownloadResponse(true, "Download completed successfully",
                        targetDirectory, null);
            } else {
                return new DownloadResponse(false, null,
                        "Download failed with exit code: " + exitCode, output.toString());
            }

        } catch (IOException | InterruptedException e) {
            log.error("Error during download: {}", e.getMessage(), e);
            return new DownloadResponse(false, null,
                    "Error: " + e.getMessage(), e.toString());
        }
    }

    private List<String> buildCommand(DownloadRequest request, String ytDlpPath, String targetDirectory) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add(request.getUrl());

        // Добавляем опции
        if (request.getFormat() != null) {
            command.add("-f");
            command.add(request.getFormat());
        }

        // УБРАЛИ outputTemplate - используем стандартное имя файла
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
                    new InputStreamReader(process.getInputStream()))) {
                return reader.readLine();
            }
        } catch (IOException e) {
            log.error("Error getting yt-dlp version: {}", e.getMessage(), e);
            return "Unknown (Error: " + e.getMessage() + ")";
        }
    }

    public List<FileInfo> getDownloadedFiles(String directory) throws IOException {
        if (directory == null || directory.trim().isEmpty()) {
            throw new IOException("Директория не указана");
        }

        List<FileInfo> files = new ArrayList<>();
        Path downloadDir = Paths.get(directory);

        if (!Files.exists(downloadDir)) {
            throw new IOException("Директория не существует: " + downloadDir.toAbsolutePath());
        }

        if (!Files.isDirectory(downloadDir)) {
            throw new IOException("Указанный путь не является директорией: " + downloadDir.toAbsolutePath());
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(downloadDir)) {
            for (Path path : stream) {
                if (!Files.isDirectory(path)) {
                    files.add(new FileInfo(
                            path.getFileName().toString(),
                            formatFileSize(Files.size(path)),
                            Files.getLastModifiedTime(path).toMillis()
                    ));
                }
            }
        }
        return files;
    }

    public ResponseEntity<Resource> downloadFile(String filename, String directory) throws IOException {
        if (directory == null || directory.trim().isEmpty()) {
            throw new IOException("Директория не указана");
        }

        Path file = Paths.get(directory).resolve(filename);
        Resource resource = new UrlResource(file.toUri());

        if (resource.exists() && resource.isReadable()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } else {
            throw new IOException("Файл не найден или недоступен для чтения");
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    @Data
    @RequiredArgsConstructor
    public static class FileInfo {
        private final String name;
        private final String size;
        private final long lastModified;
    }
}