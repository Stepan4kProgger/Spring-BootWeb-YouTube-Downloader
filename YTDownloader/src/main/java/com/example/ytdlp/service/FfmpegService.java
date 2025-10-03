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
        Thread outputReader = getOutputReader(process);

        int exitCode = process.waitFor();
        outputReader.join();

        if (exitCode != 0) {
            throw new IOException("FFmpeg merge failed with exit code: " + exitCode);
        }

        log.info("FFmpeg merge completed successfully - streams copied without re-encoding");
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