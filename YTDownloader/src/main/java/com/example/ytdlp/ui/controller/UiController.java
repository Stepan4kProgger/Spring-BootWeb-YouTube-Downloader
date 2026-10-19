package com.example.ytdlp.ui.controller;

import com.example.ytdlp.config.ApplicationConfig;
import com.example.ytdlp.service.ProcessControlService;
import com.example.ytdlp.service.ProgressTrackingService;
import com.example.ytdlp.service.YtDlpService;
import com.example.ytdlp.utils.model.DownloadProgress;
import com.example.ytdlp.utils.model.DownloadRequest;
import com.example.ytdlp.utils.model.DownloadResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class UiController {
    private final YtDlpService ytDlpService;
    private final ProcessControlService processControlService;
    private final ProgressTrackingService progressTrackingService;
    @Getter
    private final ApplicationConfig appConfig;

    public String getYtDlpVersion()  { return ytDlpService.getYtDlpVersion(); }
    public String getFfmpegVersion() { return ytDlpService.getFfmpegVersion(); }

    public List<DownloadProgress> getAllDownloads() {
        List<DownloadProgress> all = new ArrayList<>();

        progressTrackingService.getActiveDownloads().values().stream()
                .map(DownloadProgress::new).forEach(all::add);

        ytDlpService.getDownloadHistory().stream()
                .map(DownloadProgress::new).forEach(all::add);

        all.sort((a, b) -> {
            boolean aActive = isActive(a.getStatus());
            boolean bActive = isActive(b.getStatus());
            if (aActive && !bActive) return -1;
            if (!aActive && bActive) return 1;

            LocalDateTime at = a.getEndTime() != null ? a.getEndTime() : a.getStartTime();
            LocalDateTime bt = b.getEndTime() != null ? b.getEndTime() : b.getStartTime();
            if (at == null) return 1;
            if (bt == null) return -1;
            return bt.compareTo(at);
        });
        return all;
    }

    private boolean isActive(String s) {

        return "downloading".equals(s) || "paused".equals(s) || "analyzing".equals(s)
                || "merging".equals(s) || "downloading_combined".equals(s)
                || "downloading_video".equals(s) || "downloading_audio".equals(s);
    }

    public DownloadResponse startDownload(DownloadRequest req) {
        return ytDlpService.downloadVideo(req);
    }

    public boolean pause(String id)  { return ytDlpService.pauseDownload(id); }
    public boolean resume(String id) { return ytDlpService.resumeDownload(id); }
    public boolean cancel(String id) { return ytDlpService.cancelDownload(id); }
    public boolean delete(String id) { return ytDlpService.deleteDownloadedFile(id); }
    public boolean retry(String id)  { return processControlService.retryDownload(id); }

    public void saveSettings(String dir, boolean clearHistory, String quality, boolean compat) {
        appConfig.updateAllSettings(dir, clearHistory, quality, compat);
    }

    public void clearHistory() { ytDlpService.clearDownloadHistory(); }

    public void openInExplorer(String filename, String directory) throws IOException {
        ytDlpService.openFileInExplorer(filename, directory);
    }
}