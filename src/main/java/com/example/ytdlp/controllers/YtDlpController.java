package com.example.ytdlp.controllers;

import com.example.ytdlp.model.DownloadRequest;
import com.example.ytdlp.model.DownloadResponse;
import com.example.ytdlp.service.YtDlpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class YtDlpController {

    private final YtDlpService ytDlpService;

    @PostMapping
    public ResponseEntity<DownloadResponse> downloadVideo(
            @Valid @RequestBody DownloadRequest request) {
        log.info("Received download request for URL: {}", request.getUrl());
        DownloadResponse response = ytDlpService.downloadVideo(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/version")
    public ResponseEntity<String> getVersion() {
        String version = ytDlpService.getVersion();
        return ResponseEntity.ok("yt-dlp version: " + version);
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Service is running");
    }
}