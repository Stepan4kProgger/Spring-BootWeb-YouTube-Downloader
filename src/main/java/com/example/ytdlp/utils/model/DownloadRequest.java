package com.example.ytdlp.utils.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DownloadRequest {
    @NotBlank(message = "URL is required")
    private String url;

    private String downloadDirectory;
    private String format;
    private Boolean extractAudio = false;
    private String audioFormat;
    private Integer quality;
}