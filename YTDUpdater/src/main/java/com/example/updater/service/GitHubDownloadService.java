package com.example.updater.service;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class GitHubDownloadService {

    /** Считаем загрузку «зависшей», если за это время не пришло ни одного байта. */
    private static final long STALL_TIMEOUT_MS = 60_000L;
    /** Общий таймаут на загрузку (страховка от «медленного ручья»). */
    private static final long MAX_TOTAL_TIMEOUT_MS = 30L * 60L * 1000L;

    @Value("${app.update.github.jar-url}")
    private String jarUrl;

    @Value("${app.update.github.yt-dlp-url}")
    private String ytDlpUrl;

    private final HttpClient httpClient;

    @Setter
    private ProgressCallback progressCallback;

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgressUpdate(int progress, String fileName);
    }

    public GitHubDownloadService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public boolean downloadJarWithRetry(Path destinationPath) {
        return downloadFileWithRetry(jarUrl, destinationPath, "yt-downloader.jar");
    }

    public boolean downloadYtDlpWithRetry(Path destinationPath) {
        return downloadFileWithRetry(ytDlpUrl, destinationPath, "yt-dlp.exe");
    }

    public boolean downloadFileWithRetry(String fileUrl, Path destinationPath, String fileName) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info("Попытка {} из {} для {}", attempt, maxAttempts, fileName);
            if (downloadFile(fileUrl, destinationPath, fileName)) {
                return true;
            }
            if (attempt < maxAttempts) {
                log.info("Повторная попытка через 5 секунд...");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    // B4: прерывание — прекращаем ретраи.
                    Thread.currentThread().interrupt();
                    log.warn("Ожидание перед повтором прервано — прекращаем ретраи");
                    return false;
                }
            }
        }
        return false;
    }

    public boolean downloadFile(String fileUrl, Path destinationPath, String fileName) {
        boolean success = false;
        try {
            log.info("Начинаем загрузку {} по URL: {}", fileName, fileUrl);

            Path parentDir = destinationPath.toAbsolutePath().getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Создана директория: {}", parentDir.toAbsolutePath());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fileUrl))
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                log.error("Ошибка загрузки {}. Код: {}", fileName, response.statusCode());
                return false;
            }

            long fileSize = Long.parseLong(
                    response.headers().firstValue("Content-Length").orElse("0"));

            notifyProgress(0, fileName);

            try (InputStream inputStream = response.body();
                 OutputStream outputStream = Files.newOutputStream(destinationPath)) {

                long totalRead = downloadWithWatchdog(inputStream, outputStream, fileName, fileSize);

                if (fileSize > 0 && totalRead != fileSize) {
                    log.error("Размер {} не совпал: ожидалось {}, получено {}",
                            fileName, fileSize, totalRead);
                    return false;
                }
            }

            notifyProgress(100, fileName);
            log.info("Загрузка {} завершена!", fileName);
            success = true;
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Загрузка {} прервана", fileName);
            return false;
        } catch (Exception e) {
            log.error("Ошибка при загрузке {}: {}", fileName, e.getMessage(), e);
            return false;
        } finally {
            if (!success) {
                try {
                    Files.deleteIfExists(destinationPath);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private long downloadWithWatchdog(InputStream inputStream,
                                      OutputStream outputStream,
                                      String fileName,
                                      long totalSize) throws IOException {
        byte[] buffer = new byte[8192];
        long totalRead = 0;
        int lastProgress = -1;
        long startTime = System.currentTimeMillis();

        AtomicLong lastActivityRef = new AtomicLong(startTime);

        try (ScheduledExecutorService watchdog =
                     Executors.newSingleThreadScheduledExecutor(r -> {
                         Thread t = new Thread(r, "download-watchdog-" + fileName);
                         t.setDaemon(true);
                         return t;
                     })) {

            ScheduledFuture<?> watchdogTask = watchdog.scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                long idle = now - lastActivityRef.get();
                long total = now - startTime;
                if (idle > STALL_TIMEOUT_MS || total > MAX_TOTAL_TIMEOUT_MS) {
                    log.error("Загрузка {} зависла (idle={}ms, total={}ms) — прерываем",
                            fileName, idle, total);
                    try {
                        inputStream.close();   // idempotent — безопасно при гонке с try-with-resources
                    } catch (IOException ignored) {
                    }
                }
            }, 1, 1, TimeUnit.SECONDS);

            try {
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    lastActivityRef.set(System.currentTimeMillis());

                    if (totalSize > 0) {
                        int progress = (int) ((totalRead * 100) / totalSize);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            notifyProgress(progress, fileName);
                        }
                    }
                }
            } finally {
                watchdogTask.cancel(false);
            }
        }
        return totalRead;
    }

    private void notifyProgress(int progress, String fileName) {
        ProgressCallback cb = this.progressCallback;
        if (cb != null) {
            cb.onProgressUpdate(progress, fileName);
        }
    }
}