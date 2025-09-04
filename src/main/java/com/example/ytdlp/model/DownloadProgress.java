// DownloadProgress.java
package com.example.ytdlp.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
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

    @JsonIgnore // Исключаем из сериализации
    private transient Process process; // Ссылка на процесс для управления

    private boolean cancellable = true; // Можно ли отменить
    private boolean pausable = true; // Можно ли приостановить
}