package com.example.ytdlp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
class FfmpegService {
    @Value("${ffmpeg.path}") private String ffmpegPath;

    public void mergeWithFfmpeg(String videoFile, String audioFile, String outputFile)
            throws IOException, InterruptedException {

        List<String> command = buildMergeCommand(videoFile, audioFile, outputFile, false);

        log.info("Merging with FFmpeg (copy streams): {} + {} -> {}", videoFile, audioFile, outputFile);

        if (!executeFfmpegCommand(command, "merge (copy)")) {
            log.warn("Direct stream copy failed, trying with explicit mapping...");
            List<String> explicitCommand = buildMergeCommand(videoFile, audioFile, outputFile, true);
            executeFfmpegCommand(explicitCommand, "merge (explicit mapping)");
        }
    }

    private List<String> buildMergeCommand(String videoFile, String audioFile, String outputFile, boolean explicitMapping) {
        List<String> command = new ArrayList<>(List.of(ffmpegPath, "-i", videoFile, "-i", audioFile));

        if (explicitMapping) {
            command.addAll(List.of("-map", "0:v", "-map", "1:a"));
        }

        command.addAll(List.of("-c:v", "copy", "-c:a", "copy", "-y", outputFile));
        return command;
    }

    private boolean executeFfmpegCommand(List<String> command, String operation)
            throws IOException, InterruptedException {

        log.info("FFmpeg {} command: {}", operation, String.join(" ", command));

        Process process = new ProcessBuilder(command).start();
        Thread outputReader = getOutputReader(process);

        int exitCode = process.waitFor();
        outputReader.join();

        if (exitCode == 0) {
            log.info("FFmpeg {} completed successfully", operation);
            return true;
        }
        return false;
    }

    private static Thread getOutputReader(Process process) {
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
        return outputReader;
    }
}