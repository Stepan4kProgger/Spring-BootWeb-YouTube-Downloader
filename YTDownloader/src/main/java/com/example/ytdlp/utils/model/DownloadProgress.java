package com.example.ytdlp.utils.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class DownloadProgress {
    private String url;
    private String filename;
    private String status;
    private int progress;
    private int currentStage = 0;
    private int totalStages = 0;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String downloadDirectory;
    private String errorMessage;
    private String downloadId;

    @JsonIgnore
    private transient Process process;

    public DownloadProgress(DownloadProgress other) {
        this.url = other.url;
        this.filename = other.filename;
        this.status = other.status;
        this.progress = other.progress;
        this.currentStage = other.currentStage;
        this.totalStages = other.totalStages;
        this.startTime = other.startTime;
        this.endTime = other.endTime;
        this.downloadDirectory = other.downloadDirectory;
        this.errorMessage = other.errorMessage;
        this.downloadId = other.downloadId;
    }
}