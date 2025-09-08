// DownloadProgress.java
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
    private String status; // "downloading", "completed", "error", "paused", "cancelled"
    private int progress; // 0-100
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String downloadDirectory;
    private String errorMessage;
    private String downloadId; // Уникальный идентификатор загрузки

    @JsonIgnore
    private transient Process process; // Ссылка на процесс для управления

    private boolean cancellable = true;
    private boolean pausable = true;

    // Конструктор копирования без process
    public DownloadProgress(DownloadProgress other) {
        this.url = other.url;
        this.filename = other.filename;
        this.status = other.status;
        this.progress = other.progress;
        this.startTime = other.startTime;
        this.endTime = other.endTime;
        this.downloadDirectory = other.downloadDirectory;
        this.errorMessage = other.errorMessage;
        this.downloadId = other.downloadId;
        this.cancellable = other.cancellable;
        this.pausable = other.pausable;
        // process намеренно не копируется
    }
}