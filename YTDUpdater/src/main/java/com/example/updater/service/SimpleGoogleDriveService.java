package com.example.updater.service;

import com.example.updater.utils.ProgressDialog;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class SimpleGoogleDriveService {

    @Value("${app.update.google-drive.versions-file-id}")
    private String versionsFileId;

    @Getter
    @Value("${app.update.google-drive.jar-file-id}")
    private String jarFileId;

    @Getter
    @Value("${app.update.google-drive.yt-dlp-file-id}")
    private String ytDlpFileId;

    @Setter
    private ProgressDialog progressDialog;

    @Getter
    @Setter
    private volatile int currentProgress = 0;

    private static final String DOWNLOAD_URL_TEMPLATE = "https://drive.usercontent.google.com/download?id=%s&export=download&authuser=0&confirm=t&uuid=%s&at=%s";
    private final HttpClient httpClient;

    public SimpleGoogleDriveService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean downloadFile(String fileId, Path destinationPath, String fileName) {
        try {
            String downloadUrl = generateDownloadUrl(fileId);
            log.info("Начинаем загрузку {} по URL: {}", fileName, downloadUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() == 200) {
                long fileSize = Long.parseLong(
                        response.headers().firstValue("Content-Length").orElse("0"));

                // Установите неопределенный режим если размер файла неизвестен
                if (fileSize == 0 && progressDialog != null) {
                    SwingUtilities.invokeLater(() ->
                            progressDialog.setIndeterminate(true));
                }
                if (progressDialog != null) {
                    SwingUtilities.invokeLater(() -> {
                        progressDialog.setMessage("Загрузка " + fileName);
                        progressDialog.setProgress(0);
                    });
                }

                try (InputStream inputStream = response.body();
                     OutputStream outputStream = Files.newOutputStream(destinationPath)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalRead = 0;
                    long lastUpdateTime = 0;

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        int progress = fileSize > 0 ? (int) ((totalRead * 100) / fileSize) : 0;
                        setCurrentProgress(progress);

                        // Обновляем прогресс только если известен размер файла
                        if (fileSize > 0) {
                            long currentTime = System.currentTimeMillis();
                            if (progressDialog != null &&
                                    (currentTime - lastUpdateTime > 100 || totalRead == fileSize)) {
                                progress = (int) ((totalRead * 100) / fileSize);
                                int finalProgress = progress;
                                SwingUtilities.invokeLater(() ->
                                        progressDialog.setProgress(finalProgress));
                                lastUpdateTime = currentTime;
                            }
                        }
                    }
                }

                if (progressDialog != null) {
                    SwingUtilities.invokeLater(() -> {
                        progressDialog.setIndeterminate(false);
                        progressDialog.setProgress(100);
                    });
                }

                log.info("Загрузка {} завершена!", fileName);
                return true;
            } else {
                log.error("Ошибка загрузки. Код: {}", response.statusCode());
                return false;
            }
        } catch (InterruptedException e) {
            log.error("Загрузка файла {} была прервана", fileName, e);
            return false;
        } catch (Exception e) {
            log.error("Ошибка при загрузке файла {}: {}", fileName, e.getMessage(), e);
            return false;
        }
    }

    public boolean downloadFileWithRetry(String fileId, Path destinationPath, String fileName) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info("Попытка {} из {}", attempt, maxAttempts);
            if (downloadFile(fileId, destinationPath, fileName)) {
                return true;
            }

            if (attempt < maxAttempts) {
                log.info("Повторная попытка через 5 секунд...");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    log.warn("Ожидание прервано, продолжаем следующую попытку");
                }
            }
        }
        return false;
    }

    public String downloadVersionsFile() {
        try {
            String downloadUrl = generateDownloadUrl(versionsFileId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                String content = response.body();
                if (content != null && !content.trim().isEmpty()) {
                    log.info("Файл версий успешно загружен");
                    return content;
                } else {
                    log.error("Файл версий пустой");
                    return null;
                }
            } else {
                log.error("Ошибка загрузки файла версий. Код ответа: {}", response.statusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("Ошибка при загрузке файла версий: {}", e.getMessage(), e);
            return null;
        }
    }

    private String generateDownloadUrl(String fileId) {
        // Генерируем UUID и текущее время для параметров
        String uuid = UUID.randomUUID().toString();
        String at = System.currentTimeMillis() + "";

        return String.format(DOWNLOAD_URL_TEMPLATE, fileId, uuid, at);
    }
}