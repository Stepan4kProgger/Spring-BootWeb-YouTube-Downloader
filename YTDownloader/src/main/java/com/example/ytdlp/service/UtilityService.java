package com.example.ytdlp.service;

import com.example.ytdlp.utils.model.VideoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
class UtilityService {
    @Value("${yt-dlp.path}") private String ytDlpPath;
    @Value("${ffmpeg.path}") private String ffmpegPath;

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

    public String generateFinalFilenameFromVideoInfo(VideoInfo videoInfo) {
        String title = videoInfo.getTitle();
        if (title == null) {
            title = "video_" + System.currentTimeMillis();
        }
        title = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        return title + ".mp4";
    }
}