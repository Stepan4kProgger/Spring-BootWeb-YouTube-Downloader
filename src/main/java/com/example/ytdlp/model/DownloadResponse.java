package com.example.ytdlp.model;

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
    private String error;
}