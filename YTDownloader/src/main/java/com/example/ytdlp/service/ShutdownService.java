package com.example.ytdlp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ShutdownService {
    private final YtDlpService ytDlpService;
    private final ProgressTrackingService progressTrackingService;

    private final AtomicBoolean started  = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public ShutdownService(YtDlpService ytDlpService,
                           ProgressTrackingService progressTrackingService) {
        this.ytDlpService = ytDlpService;
        this.progressTrackingService = progressTrackingService;
    }

    /**
     * Останавливает все активные процессы (с убийством дерева), добивает
     * оставшиеся ProcessHandle и очищает карту процессов.
     * Безопасно вызывать многократно — работу выполняет только первый вызов,
     * остальные ждут его завершения (с таймаутом 10 с).
     */
    public void shutdown() {
        if (!started.compareAndSet(false, true)) {
            waitForCompletion();
            return;
        }
        try {
            log.info("ShutdownService: stopping all downloads...");
            try {
                ytDlpService.stopAllDownloads();
            } catch (Exception e) {
                log.error("Error stopping downloads", e);
            }

            try {
                progressTrackingService.getActiveProcesses().values().forEach(p -> {
                    if (p == null) return;
                    try { p.descendants().forEach(ProcessHandle::destroyForcibly); } catch (Exception ignored) {}
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                });
                progressTrackingService.getActiveProcesses().clear();
            } catch (Exception e) {
                log.error("Error in final process cleanup", e);
            }

            log.info("ShutdownService: shutdown complete");
        } finally {
            finished.set(true);
        }
    }

    private void waitForCompletion() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!finished.get() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}