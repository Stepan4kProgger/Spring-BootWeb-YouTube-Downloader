package com.example.ytdlp.utils.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadResponse {
    private boolean success;
    private String message;
    private String outputPath;
    private String filename;
    private String error;

    public DownloadResponse(boolean success, String message, String outputPath, String filename) {
        this(success, message, outputPath, filename, null);
    }
}