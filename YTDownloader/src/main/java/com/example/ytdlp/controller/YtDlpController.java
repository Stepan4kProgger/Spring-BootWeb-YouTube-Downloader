package com.example.ytdlp.controller;

import com.example.ytdlp.service.YtDlpService;
import com.example.ytdlp.utils.model.DownloadRequest;
import com.example.ytdlp.utils.model.DownloadResponse;
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
            @Valid @RequestBody DownloadRequest request,
            @RequestHeader(value = "X-YouTube-Cookies", required = false) String cookiesHeader) {

        log.info("Download request: {}", request.getUrl());

        if ((request.getCookies() == null || request.getCookies().isBlank())
                && cookiesHeader != null && !cookiesHeader.isBlank()) {
            request.setCookies(cookiesHeader);
        }

        if (request.getCookies() != null) {
            log.info("Cookies provided (length: {})", request.getCookies().length());
        } else {
            log.warn("No cookies provided");
        }

        return ResponseEntity.ok(ytDlpService.downloadVideo(request));
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    @GetMapping("/version")
    public ResponseEntity<String> version() {
        return ResponseEntity.ok(ytDlpService.getYtDlpVersion());
    }
}