package com.example.ytdlp.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DownloadProgress {
    private String url;
    private String filename;
    private String status; // "downloading", "completed", "error"
    private int progress; // 0-100
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String downloadDirectory;
    private String errorMessage;
}