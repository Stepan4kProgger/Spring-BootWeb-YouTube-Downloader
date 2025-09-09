package com.example.ytdlp.controller;

import com.example.ytdlp.utils.model.DownloadRequest;
import com.example.ytdlp.utils.model.DownloadResponse;
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
    public ResponseEntity<DownloadResponse> downloadVideo(@Valid @RequestBody DownloadRequest request) {
        log.info("Received download request for URL: {}", request.getUrl());
        return ResponseEntity.ok(ytDlpService.downloadVideo(request));
    }

    @GetMapping("/version")
    public ResponseEntity<String> getVersion() {
        return ResponseEntity.ok("yt-dlp version: " + ytDlpService.getVersion());
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Service is running");
    }
}