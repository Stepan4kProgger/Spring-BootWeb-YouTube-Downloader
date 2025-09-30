package com.example.ytdlp.controller;

import com.example.ytdlp.utils.model.DownloadRequest;
import com.example.ytdlp.utils.model.DownloadResponse;
import com.example.ytdlp.service.YtDlpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class YtDlpController {
    private final YtDlpService ytDlpService;

    @PostMapping
    public ResponseEntity<DownloadResponse> downloadVideo(@Valid @RequestBody DownloadRequest request,
                                                          @RequestHeader(value = "X-YouTube-Cookies", required = false) String cookiesHeader) {
        log.info("Received download request for URL: {}", request.getUrl());

        // Улучшенное логирование куки
        log.info("Cookies from header: {}", cookiesHeader != null ?
                (cookiesHeader.length() > 100 ? cookiesHeader.substring(0, 100) + "..." : cookiesHeader) : "NULL");

        log.info("Cookies from request body: {}", request.getCookies() != null ?
                (request.getCookies().length() > 100 ? request.getCookies().substring(0, 100) + "..." : request.getCookies()) : "NULL");

        // Приоритет: куки из тела запроса, затем из заголовка
        if (request.getCookies() == null || request.getCookies().trim().isEmpty()) {
            if (cookiesHeader != null && !cookiesHeader.trim().isEmpty()) {
                request.setCookies(cookiesHeader);
                log.info("Using cookies from header (length: {})", cookiesHeader.length());
            } else {
                log.warn("No cookies provided in request");
            }
        } else {
            log.info("Using cookies from request body (length: {})", request.getCookies().length());
        }

        // Дополнительная проверка формата куки
        if (request.getCookies() != null) {
            validateCookiesFormat(request.getCookies());
        }

        return ResponseEntity.ok(ytDlpService.downloadVideo(request));
    }

    // НОВЫЙ МЕТОД: Валидация формата куки
    private void validateCookiesFormat(String cookies) {
        if (cookies == null || cookies.trim().isEmpty()) {
            log.warn("Cookies are empty");
            return;
        }

        try {
            String[] cookiePairs = cookies.split(";");
            log.info("Cookie validation: found {} cookie pairs", cookiePairs.length);

            for (String cookiePair : cookiePairs) {
                cookiePair = cookiePair.trim();
                if (!cookiePair.isEmpty()) {
                    String[] parts = cookiePair.split("=", 2);
                    if (parts.length == 2) {
                        String name = parts[0].trim();
                        String value = parts[1].trim();
                        log.debug("Valid cookie: {}={} (length: {})", name,
                                value.length() > 10 ? value.substring(0, 10) + "..." : value,
                                value.length());
                    } else {
                        log.warn("Invalid cookie format: {}", cookiePair);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error validating cookies format: {}", e.getMessage());
        }
    }

    @PostMapping("/test-cookies")
    public ResponseEntity<Map<String, Object>> testCookies(@RequestBody DownloadRequest request,
                                                           @RequestHeader(value = "X-YouTube-Cookies", required = false) String cookiesHeader) {
        Map<String, Object> response = new HashMap<>();

        try {
            response.put("success", true);
            response.put("url", request.getUrl());
            response.put("cookies_from_body", request.getCookies());
            response.put("cookies_from_header", cookiesHeader);
            response.put("body_cookies_length", request.getCookies() != null ? request.getCookies().length() : 0);
            response.put("header_cookies_length", cookiesHeader != null ? cookiesHeader.length() : 0);

            if (request.getCookies() != null) {
                String[] cookiePairs = request.getCookies().split(";");
                response.put("cookie_pairs_count", cookiePairs.length);

                List<Map<String, String>> cookiesList = new ArrayList<>();
                for (String cookiePair : cookiePairs) {
                    cookiePair = cookiePair.trim();
                    if (!cookiePair.isEmpty()) {
                        String[] parts = cookiePair.split("=", 2);
                        if (parts.length == 2) {
                            Map<String, String> cookieInfo = new HashMap<>();
                            cookieInfo.put("name", parts[0].trim());
                            cookieInfo.put("value_length", String.valueOf(parts[1].trim().length()));
                            cookiesList.add(cookieInfo);
                        }
                    }
                }
                response.put("cookies_details", cookiesList);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
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