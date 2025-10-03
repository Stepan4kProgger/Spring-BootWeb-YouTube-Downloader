package com.example.ytdlp.service;

import com.example.ytdlp.config.ApplicationConfig;
import com.example.ytdlp.utils.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.nio.file.Files.createTempFile;

@Slf4j
@Service
@RequiredArgsConstructor
class DownloadManagementService {
    private final VideoInfoService videoInfoService;
    private final FormatSelectionService formatSelectionService;
    private final ProgressTrackingService progressTrackingService;
    private final HistoryService historyService;
    private final TempFileService tempFileService;
    private final FfmpegService ffmpegService;
    private final UtilityService utilityService;

    @Value("${yt-dlp.path}") private String ytDlpPath;
    @Value("${ffmpeg.path}") private String ffmpegPath;

    @Autowired private ApplicationConfig appConfig;

    public DownloadResponse downloadVideo(DownloadRequest request) {
        String downloadId = UUID.randomUUID().toString();

        // Устанавливаем директорию из конфига, если не указана в запросе
        if (request.getDownloadDirectory() == null || request.getDownloadDirectory().trim().isEmpty()) {
            request.setDownloadDirectory(appConfig.getDirectory());
        }

        DownloadProgress progress = progressTrackingService.initializeProgress(downloadId, request);

        try {
            VideoInfo videoInfo = videoInfoService.getVideoInfo(request);

            // Используем качество из конфига, если не указано в запросе
            String quality = (request.getFormat() == null || request.getFormat().trim().isEmpty())
                    ? appConfig.getQuality()
                    : request.getFormat();

            FormatSelection formats = formatSelectionService.selectFormats(videoInfo, quality);

            Path finalFile = executeDownload(request, videoInfo, formats, progress);
            return completeDownload(progress, finalFile);

        } catch (Exception e) {
            return handleError(downloadId, progress, e);
        }
    }

    private Path executeDownload(DownloadRequest request, VideoInfo videoInfo,
                                 FormatSelection formats, DownloadProgress progress) throws Exception {
        Path videoTempFile = null;
        Path audioTempFile = null;
        Path combinedTempFile = null;
        Path cookiesFile = null;
        Path finalOutputFile;

        try {
            String targetDirectory = request.getDownloadDirectory();

            // Проверяем существование yt-dlp и ffmpeg
            if (!Files.exists(Paths.get(ytDlpPath))) {
                throw new IOException("yt-dlp not found at: " + ytDlpPath);
            }
            if (!Files.exists(Paths.get(ffmpegPath))) {
                throw new IOException("ffmpeg not found at: " + ffmpegPath);
            }

            // Создаем директорию для загрузок
            Path downloadDir = Paths.get(targetDirectory);
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir);
                log.info("Created download directory: {}", downloadDir);
            }

            // Создаем файл куки если нужно
            cookiesFile = tempFileService.createCookiesFile(request);

            // Генерируем финальное имя файла
            String finalFilename = utilityService.generateFinalFilenameFromVideoInfo(videoInfo);
            finalOutputFile = downloadDir.resolve(finalFilename);

            // Если файл уже существует, добавляем суффикс
            if (Files.exists(finalOutputFile)) {
                String baseName = finalFilename.substring(0, finalFilename.lastIndexOf('.'));
                String extension = finalFilename.substring(finalFilename.lastIndexOf('.'));
                finalFilename = baseName + "_" + System.currentTimeMillis() + extension;
                finalOutputFile = downloadDir.resolve(finalFilename);
                log.info("File already exists, using new name: {}", finalFilename);
            }

            if (formats.isCombined()) {
                // Комбинированный формат
                log.info("Using COMBINED format download");
                progressTrackingService.updateProgress(progress.getDownloadId(), "downloading_combined", 20);

                String tempPrefix = "temp_" + progress.getDownloadId() + "_";
                combinedTempFile = createTempFile(downloadDir, tempPrefix + "combined", ".mp4");

                downloadFormat(request.getUrl(), formats.getVideoFormatId(), formats.getVideoUrl(),
                        combinedTempFile.toString(), cookiesFile, progress, 20, 90);

                Files.move(combinedTempFile, finalOutputFile, StandardCopyOption.REPLACE_EXISTING);

            } else {
                // Раздельные форматы
                log.info("Using SEPARATE formats download");

                String tempPrefix = "temp_" + progress.getDownloadId() + "_";
                videoTempFile = createTempFile(downloadDir, tempPrefix + "video", ".mp4");
                audioTempFile = createTempFile(downloadDir, tempPrefix + "audio", ".m4a");

                // Скачиваем видео
                progressTrackingService.updateProgress(progress.getDownloadId(), "downloading_video", 30);
                downloadFormat(request.getUrl(), formats.getVideoFormatId(), formats.getVideoUrl(),
                        videoTempFile.toString(), cookiesFile, progress, 30, 60);

                // Скачиваем аудио
                progressTrackingService.updateProgress(progress.getDownloadId(), "downloading_audio", 60);
                downloadFormat(request.getUrl(), formats.getAudioFormatId(), formats.getAudioUrl(),
                        audioTempFile.toString(), cookiesFile, progress, 60, 90);

                // Объединяем через FFmpeg
                progressTrackingService.updateProgress(progress.getDownloadId(), "merging", 90);
                ffmpegService.mergeWithFfmpeg(videoTempFile.toString(), audioTempFile.toString(),
                        finalOutputFile.toString());
            }

            return finalOutputFile;

        } finally {
            // Очистка временных файлов
            if (formats != null && formats.isCombined()) {
                tempFileService.cleanupTempFiles(combinedTempFile, cookiesFile);
            } else {
                tempFileService.cleanupTempFiles(videoTempFile, audioTempFile, cookiesFile);
            }
        }
    }

    private DownloadResponse completeDownload(DownloadProgress progress, Path finalFile) {
        progress.setStatus("completed");
        progress.setProgress(100);
        progress.setFilename(finalFile.getFileName().toString());
        progress.setEndTime(LocalDateTime.now());

        historyService.addToDownloadHistory(progress);
        progressTrackingService.removeProgress(progress.getDownloadId());

        return new DownloadResponse(true, "Download completed successfully",
                finalFile.getParent().toString(), progress.getFilename(), null);
    }

    private DownloadResponse handleError(String downloadId, DownloadProgress progress, Exception e) {
        log.error("Download failed: {}", e.getMessage(), e);

        if (progress != null) {
            progress.setStatus("error");
            progress.setErrorMessage("Download process error: " + e.getMessage());
            progress.setEndTime(LocalDateTime.now());

            historyService.addToDownloadHistory(progress);
            progressTrackingService.removeProgress(downloadId);
        }

        return new DownloadResponse(false, null, null, "unknown",
                "Download process error: " + e.getMessage());
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

    public void downloadFormat(String url, String formatId, String formatUrl, String outputFile,
                               Path cookiesFile, DownloadProgress progress,
                               int startProgress, int endProgress) throws IOException, InterruptedException {// ПРОВЕРКА: Если файл уже существует и он пустой, удаляем его
        File output = new File(outputFile);
        Path outputFilePath = Paths.get(outputFile);
        if (output.exists() && output.length() == 0) {
            log.warn("Found empty existing file, deleting: {}", outputFile);
            Files.deleteIfExists(outputFilePath);
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
        progressTrackingService.getActiveProcesses().put(progress.getDownloadId(), process);

        // Чтение вывода для прогресса
        Thread progressThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("Download output: {}", line);
                    if (line.contains("[download]")) {
                        int currentProgress = progressTrackingService.parseProgressFromLine(line);
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
        Thread errorThread = getErrorThread(process);

        int exitCode = process.waitFor();
        progressThread.join();
        errorThread.join();
        progressTrackingService.getActiveProcesses().remove(progress.getDownloadId());

        // ПРОВЕРКА: Убедимся, что файл действительно скачан и не пустой
        File downloadedFile = new File(outputFile);
        if (!downloadedFile.exists()) {
            throw new IOException("Downloaded file does not exist: " + outputFile);
        }

        if (downloadedFile.length() == 0) {
            // Файл пустой - удаляем его и бросаем исключение
            Files.deleteIfExists(outputFilePath);
            throw new IOException("Downloaded file is empty (0 bytes): " + outputFile);
        }

        log.info("Download completed successfully: {} (size: {} bytes)",
                outputFile, downloadedFile.length());

        if (exitCode != 0) {
            throw new IOException("Download failed with exit code: " + exitCode);
        }

    }

    // Метод для возобновления загрузки
    public void resumeDownload(DownloadProgress progress) {
        DownloadRequest request = createRequestFromProgress(progress);
        downloadVideo(request);
    }

    private static Thread getErrorThread(Process process) {
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
        return errorThread;
    }

    private DownloadRequest createRequestFromProgress(DownloadProgress progress) {
        DownloadRequest request = new DownloadRequest();
        request.setUrl(progress.getUrl());
        request.setDownloadDirectory(progress.getDownloadDirectory());
        return request;
    }
}