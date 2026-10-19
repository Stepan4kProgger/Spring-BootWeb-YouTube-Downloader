package com.example.ytdlp.service;

import com.example.ytdlp.utils.model.DownloadRequest;
import com.example.ytdlp.utils.model.VideoInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoInfoService {
    private final TempFileService tempFileService;

    @Value("${yt-dlp.path}") private String ytDlpPath;

    public VideoInfo getVideoInfo(DownloadRequest request) throws IOException, InterruptedException {
        List<String> command = buildVideoInfoCommand(request);
        ProcessBuilder processBuilder = new ProcessBuilder(command);

        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();

        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

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
            log.error("Error output: {}", errorOutput);
            throw new IOException("Failed to get video info, exit code: " + exitCode + ". Error: " + errorOutput.toString());
        }

        if (output.isEmpty()) {
            throw new IOException("Video info command returned empty output");
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            VideoInfo videoInfo = objectMapper.readValue(output.toString(), VideoInfo.class);

            log.info("Video info retrieved: '{}' (ID: {}), {} formats available",
                    videoInfo.getTitle(), videoInfo.getId(),
                    videoInfo.getFormats() != null ? videoInfo.getFormats().size() : 0);

            return videoInfo;
        } catch (Exception e) {
            log.error("Failed to parse video info JSON: {}", output.toString());
            throw new IOException("Failed to parse video info JSON: " + e.getMessage());
        }
    }

    private List<String> buildVideoInfoCommand(DownloadRequest request) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add(request.getUrl());

        if (request.getCookies() != null && !request.getCookies().trim().isEmpty()) {
            Path cookiesFile = Files.createTempFile("cookies_info_" + System.currentTimeMillis(), ".txt");
            String formattedCookies = tempFileService.formatCookiesForNetscape(request.getCookies());
            Files.writeString(cookiesFile, formattedCookies, StandardCharsets.UTF_8);
            cookiesFile.toFile().deleteOnExit();

            command.add("--cookies");
            command.add(cookiesFile.toString());
        }

        command.add("--js-runtimes");
        command.add("node");
        command.add("--remote-components");
        command.add("ejs:github");

        command.add("--dump-json");
        command.add("--no-playlist");
        command.add("--no-check-certificate");
        command.add("--prefer-insecure");
        command.add("--force-ipv4");
        command.add("--socket-timeout");
        command.add("30");

        log.info("Video info command: {}", String.join(" ", command));
        return command;
    }
}