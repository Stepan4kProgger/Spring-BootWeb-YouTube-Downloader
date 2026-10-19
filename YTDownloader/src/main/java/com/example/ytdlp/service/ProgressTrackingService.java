package com.example.ytdlp.service;

import com.example.ytdlp.config.ApplicationConfig;
import com.example.ytdlp.utils.model.DownloadProgress;
import com.example.ytdlp.utils.model.DownloadRequest;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Getter
public class ProgressTrackingService {
    private final Map<String, DownloadProgress> activeDownloads = new ConcurrentHashMap<>();
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Pattern PROGRESS_PATTERN = Pattern.compile("\\[download]\\s+(\\d+\\.?\\d*)%");

    @Autowired private ApplicationConfig appConfig;

    public DownloadProgress initializeProgress(String downloadId, DownloadRequest request) {
        DownloadProgress progress = new DownloadProgress();
        progress.setDownloadId(downloadId);
        progress.setUrl(request.getUrl());
        progress.setStatus("analyzing");
        progress.setProgress(0);
        progress.setCurrentStage(1);
        progress.setTotalStages(0);
        progress.setStartTime(LocalDateTime.now());
        progress.setDownloadDirectory(appConfig.getDirectory());

        activeDownloads.put(downloadId, progress);
        return progress;
    }

    public void updateStage(DownloadProgress progress, String status, int currentStage) {
        progress.setStatus(status);
        progress.setCurrentStage(currentStage);
        progress.setProgress(0);
    }

    public DownloadProgress getProgress(String downloadId) {
        return activeDownloads.get(downloadId);
    }

    public void removeProgress(String downloadId) {
        activeDownloads.remove(downloadId);
    }

    int parseProgressFromLine(String line) {
        Matcher matcher = PROGRESS_PATTERN.matcher(line);
        if (matcher.find()) {
            return (int) Float.parseFloat(matcher.group(1));
        }
        return -1;
    }
}