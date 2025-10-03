package com.example.ytdlp.service;

import com.example.ytdlp.config.ApplicationConfig;
import com.example.ytdlp.utils.model.DownloadProgress;
import com.example.ytdlp.utils.model.DownloadRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
class ProcessControlService {
    private final ProgressTrackingService progressTrackingService;
    private final HistoryService historyService;
    private final DownloadManagementService downloadManagementService; // для resume

    @Autowired
    private ApplicationConfig appConfig;

    public boolean pauseDownload(String downloadId) {
        DownloadProgress progress = progressTrackingService.getActiveDownloads().get(downloadId);
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

                progressTrackingService.getActiveProcesses().remove(downloadId);

                log.info("Download paused: {}", downloadId);
                return true;
            } catch (Exception e) {
                log.error("Error pausing download: {}", e.getMessage());
            }
        }
        return false;
    }

    public boolean resumeDownload(String downloadId) {
        try {
            DownloadProgress progress = progressTrackingService.getProgress(downloadId);
            if (progress != null && "paused".equals(progress.getStatus())) {
                downloadManagementService.resumeDownload(progress);
                progressTrackingService.removeProgress(downloadId);
                return true;
            }
        } catch (Exception e) {
            log.error("Error resuming download: {}", e.getMessage());
        }
        return false;
    }

    public boolean cancelDownload(String downloadId) {
        DownloadProgress progress = progressTrackingService.getActiveDownloads().get(downloadId);
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
                progressTrackingService.getActiveProcesses().remove(downloadId);

                // Сохраняем в историю
                historyService.addToDownloadHistory(progress);

                // Удаляем из активных загрузок
                progressTrackingService.getActiveDownloads().remove(downloadId);

                log.info("Download cancelled and partial file deleted: {}", downloadId);
                return true;
            } catch (Exception e) {
                log.error("Error cancelling download: {}", e.getMessage());
            }
        }
        return false;
    }

    public boolean deleteDownloadedFile(String downloadId) {
        // Ищем в активных загрузках
        DownloadProgress progress = progressTrackingService.getActiveDownloads().get(downloadId);
        if (progress == null) {
            // Ищем в истории
            progress = historyService.getDownloadHistory().stream()
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
                historyService.getDownloadHistory().removeIf(p -> downloadId.equals(p.getDownloadId()));
                historyService.saveDownloadHistory();

                // Удаляем из активных, если там остался
                progressTrackingService.getActiveDownloads().remove(downloadId);

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
        stopAllProcesses();
        updateActiveDownloadsStatus();
        log.info("All downloads stopped successfully");
    }

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

    private void stopAllProcesses() {
        int stoppedCount = 0;
        for (Map.Entry<String, Process> entry : progressTrackingService.getActiveProcesses().entrySet()) {
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
        progressTrackingService.getActiveProcesses().clear();
        log.info("Stopped {} processes", stoppedCount);
    }

    private void updateActiveDownloadsStatus() {
        for (DownloadProgress progress : progressTrackingService.getActiveDownloads().values()) {
            if ("downloading".equals(progress.getStatus()) || "paused".equals(progress.getStatus())) {
                progress.setStatus("cancelled");
                progress.setCancellable(false);
                progress.setPausable(false);
                progress.setEndTime(LocalDateTime.now());
                progress.setErrorMessage("Загрузка прервана при завершении приложения");

                historyService.addToDownloadHistory(progress);
            }
        }
        progressTrackingService.getActiveDownloads().clear();
    }

    private void forceStopProcess(Process process) {
        try {
            process.destroyForcibly();
        } catch (Exception e) {
            log.error("Failed to force destroy process: {}", e.getMessage());
        }
    }
}