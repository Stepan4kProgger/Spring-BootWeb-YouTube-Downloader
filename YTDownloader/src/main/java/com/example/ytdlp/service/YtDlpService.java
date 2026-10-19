package com.example.ytdlp.service;

import com.example.ytdlp.config.ApplicationConfig;
import com.example.ytdlp.utils.model.DownloadProgress;
import com.example.ytdlp.utils.model.DownloadRequest;
import com.example.ytdlp.utils.model.DownloadResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class YtDlpService {
    private final DownloadManagementService downloadManagementService;
    private final ProgressTrackingService progressTrackingService;
    private final HistoryService historyService;
    private final ProcessControlService processControlService;
    private final UtilityService utilityService;

    @Autowired private ApplicationConfig appConfig;

    @PostConstruct
    public void init() {
        appConfig.loadConfig();

        if (appConfig.isClearHistoryOnStartup()) {
            clearDownloadHistory();
            log.info("Download history cleared on startup as configured");
        } else {
            historyService.loadDownloadHistory();
        }
    }

    public DownloadResponse downloadVideo(DownloadRequest request) {
        return downloadManagementService.downloadVideo(request);
    }

    public String getYtDlpVersion() {
        return utilityService.getYtDlpVersion();
    }

    public String getFfmpegVersion() {
        return utilityService.getFfmpegVersion();
    }

    public void clearDownloadHistory() {
        historyService.clearDownloadHistory();
    }

    public List<DownloadProgress> getDownloadHistory() {
        return historyService.getDownloadHistory();
    }

    public boolean pauseDownload(String downloadId) {
        return processControlService.pauseDownload(downloadId);
    }

    public boolean resumeDownload(String downloadId) {
        return processControlService.resumeDownload(downloadId);
    }

    public boolean cancelDownload(String downloadId) {
        return processControlService.cancelDownload(downloadId);
    }

    public boolean deleteDownloadedFile(String downloadId) {
        return processControlService.deleteDownloadedFile(downloadId);
    }

    public void stopAllDownloads() {
        processControlService.stopAllDownloads();
    }

    public void openFileInExplorer(String filename, String directory) throws IOException {
        utilityService.openFileInExplorer(filename, directory);
    }
}