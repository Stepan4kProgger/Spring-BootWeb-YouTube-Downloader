package com.example.ytdlp.service;

import com.example.ytdlp.utils.model.DownloadProgress;
import com.example.ytdlp.utils.model.DownloadRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessControlService {
    private final ProgressTrackingService progressTrackingService;
    private final HistoryService historyService;
    private final DownloadManagementService downloadManagementService;

    public boolean pauseDownload(String downloadId) {
        DownloadProgress progress = progressTrackingService.getActiveDownloads().get(downloadId);
        if (progress != null && progress.getProcess() != null &&
                "downloading".equals(progress.getStatus())) {
            try {
                Process process = progress.getProcess();
                if (process.isAlive()) {
                    killProcessTree(process);
                }
                progress.setStatus("paused");
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

    public boolean retryDownload(String downloadId) {
        try {
            DownloadProgress progress = historyService.getDownloadHistory().stream()
                    .filter(p -> downloadId.equals(p.getDownloadId()))
                    .findFirst()
                    .orElse(null);

            if (progress != null && "error".equals(progress.getStatus())) {
                String url = progress.getUrl();
                historyService.getDownloadHistory().removeIf(p -> downloadId.equals(p.getDownloadId()));
                historyService.saveDownloadHistory();
                deletePartialFile(progress);

                DownloadRequest request = new DownloadRequest();
                request.setUrl(url);
                downloadManagementService.downloadVideo(request);
                log.info("Retried download for URL: {}", url);
                return true;
            }
        } catch (Exception e) {
            log.error("Error retrying download {}: {}", downloadId, e.getMessage(), e);
        }
        return false;
    }

    public boolean cancelDownload(String downloadId) {
        DownloadProgress progress = progressTrackingService.getActiveDownloads().get(downloadId);
        if (progress != null && progress.getProcess() != null &&
                ("downloading".equals(progress.getStatus()) || "paused".equals(progress.getStatus()))) {
            try {
                Process process = progress.getProcess();
                if (process.isAlive()) {
                    killProcessTree(process);
                }
                deletePartialFile(progress);

                progress.setStatus("cancelled");
                progress.setEndTime(LocalDateTime.now());
                progress.setErrorMessage("Загрузка отменена пользователем");

                progressTrackingService.getActiveProcesses().remove(downloadId);
                historyService.addToDownloadHistory(progress);
                progressTrackingService.getActiveDownloads().remove(downloadId);

                log.info("Download cancelled: {}", downloadId);
                return true;
            } catch (Exception e) {
                log.error("Error cancelling download: {}", e.getMessage());
            }
        }
        return false;
    }

    public boolean deleteDownloadedFile(String downloadId) {
        DownloadProgress progress = progressTrackingService.getActiveDownloads().get(downloadId);
        if (progress == null) {
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
                historyService.getDownloadHistory().removeIf(p -> downloadId.equals(p.getDownloadId()));
                historyService.saveDownloadHistory();
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

    private void stopAllProcesses() {
        ArrayList<Map.Entry<String, Process>> entries =
                new ArrayList<>(progressTrackingService.getActiveProcesses().entrySet());

        if (entries.isEmpty()) {
            progressTrackingService.getActiveProcesses().clear();
            return;
        }

        for (Map.Entry<String, Process> e : entries) {
            Process p = e.getValue();
            if (p == null || !p.isAlive()) continue;
            try { p.descendants().forEach(ProcessHandle::destroy); } catch (Exception ignored) {}
            try { p.destroy(); } catch (Exception ignored) {}
        }

        long deadline = System.currentTimeMillis() + 1000L;
        for (Map.Entry<String, Process> e : entries) {
            Process p = e.getValue();
            if (p == null) continue;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try { p.waitFor(remaining, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        }

        for (Map.Entry<String, Process> e : entries) {
            Process p = e.getValue();
            if (p == null || !p.isAlive()) continue;
            try { p.descendants().forEach(ProcessHandle::destroyForcibly); } catch (Exception ignored) {}
            try { p.destroyForcibly(); } catch (Exception ignored) {}
        }

        for (Map.Entry<String, Process> e : entries) {
            Process p = e.getValue();
            if (p == null) continue;
            try { p.waitFor(500, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        }

        progressTrackingService.getActiveProcesses().clear();
    }

    private void killProcessTree(Process process) {
        if (process == null) return;
        try { process.descendants().forEach(ProcessHandle::destroy); } catch (Exception ignored) {}
        try { process.destroy(); } catch (Exception ignored) {}

        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                try { process.descendants().forEach(ProcessHandle::destroyForcibly); } catch (Exception ignored) {}
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            try { process.destroyForcibly(); } catch (Exception ignored2) {}
        }
    }

    private void updateActiveDownloadsStatus() {
        for (DownloadProgress progress : progressTrackingService.getActiveDownloads().values()) {
            if ("downloading".equals(progress.getStatus()) || "paused".equals(progress.getStatus())) {
                progress.setStatus("cancelled");
                progress.setEndTime(LocalDateTime.now());
                progress.setErrorMessage("Загрузка прервана при завершении приложения");
                historyService.addToDownloadHistory(progress);
            }
        }
        progressTrackingService.getActiveDownloads().clear();
    }

    private void deletePartialFile(DownloadProgress progress) {
        try {
            if (progress.getFilename() != null && !progress.getFilename().equals("unknown")) {
                Path filePath = Paths.get(progress.getDownloadDirectory(), progress.getFilename());
                Files.deleteIfExists(filePath);
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                        Paths.get(progress.getDownloadDirectory()),
                        progress.getFilename() + ".*")) {
                    for (Path tempFile : stream) {
                        if (tempFile.toString().endsWith(".part") ||
                                tempFile.toString().contains(progress.getFilename() + ".")) {
                            Files.deleteIfExists(tempFile);
                        }
                    }
                }
                String tempPrefix = "temp_" + progress.getDownloadId() + "_";
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                        Paths.get(progress.getDownloadDirectory()), tempPrefix + "*")) {
                    for (Path tempFile : stream) {
                        Files.deleteIfExists(tempFile);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not delete partial file for download: {}", progress.getDownloadId(), e);
        }
    }
}