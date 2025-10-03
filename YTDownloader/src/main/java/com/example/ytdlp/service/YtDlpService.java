package com.example.ytdlp.service;

import com.example.ytdlp.utils.model.*;
import com.example.ytdlp.config.ApplicationConfig;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class YtDlpService {
    private final DownloadManagementService downloadManagementService;
    private final ProgressTrackingService progressTrackingService;
    private final HistoryService historyService;
    private final FileSystemService fileSystemService;
    private final ProcessControlService processControlService;
    private final UtilityService utilityService;

    @Autowired
    private ApplicationConfig appConfig;

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

    // Метод для получения списка доступных дисков/папок
    public List<String> getAvailableDrives() {
        return fileSystemService.getAvailableDrives();
    }

    // Метод для получения содержимого директории
    public List<DirectoryItem> listDirectory(String path) throws IOException {
        return fileSystemService.listDirectory(path);
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
        return downloadManagementService.downloadVideo(request);
    }

    public String getYtDlpVersion() {
        return utilityService.getYtDlpVersion();
    }

    // Метод получения версии ffmpeg
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

    public Map<String, String> getActiveProcessesInfo() {
        Map<String, String> info = new HashMap<>();
        for (Map.Entry<String, Process> entry : progressTrackingService.getActiveProcesses().entrySet()) {
            Process process = entry.getValue();
            info.put(entry.getKey(),
                    process != null ?
                            "Alive: " + process.isAlive() :
                            "Null process");
        }
        return info;
    }

    public void stopAllDownloads() {
        processControlService.stopAllDownloads();
    }

    public void openFileInExplorer(String filename, String directory) throws IOException {
        utilityService.openFileInExplorer(filename, directory);
    }
}