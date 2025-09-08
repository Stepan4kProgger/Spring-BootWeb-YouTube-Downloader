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
    private String filename; // Добавляем поле для имени файла
    private String error;

    // Конструктор для успешного ответа
    public DownloadResponse(boolean success, String message, String outputPath, String filename) {
        this.success = success;
        this.message = message;
        this.outputPath = outputPath;
        this.filename = filename;
        this.error = null;
    }
}