package com.example.updater.service;

import com.example.updater.UpdateInstallerApplication;
import com.example.updater.model.AppVersions;
import com.example.updater.utils.ProgressDialog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateService {

    private final GitHubDownloadService gitHubDownloadService;
    private final VersionService versionService;

    @Value("${app.update.github.jar-url}")
    private String jarUrl;

    @Value("${app.update.github.yt-dlp-url}")
    private String ytDlpUrl;

    @Value("${app.update.local.jar-path}")
    private String jarPath;

    @Value("${app.update.local.yt-dlp-path}")
    private String ytDlpPath;

    @Value("${app.update.local.versions-path}")
    private String versionsPath;

    private volatile ProgressDialog progressDialog;

    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void checkForUpdatesOnStartup() {
        try {
            SwingUtilities.invokeAndWait(() -> progressDialog = new ProgressDialog());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Прервано создание диалога прогресса", e);
            new Thread(this::performUpdateCheck, "update-check").start();
            return;
        } catch (Exception e) {
            log.error("Не удалось создать диалог прогресса", e);
        }
        SwingUtilities.invokeLater(() -> {
            ProgressDialog d = progressDialog;
            if (d != null) {
                d.setVisible(true);
            }
        });
        new Thread(this::performUpdateCheck, "update-check").start();
    }

    private void performUpdateCheck() {
        if (!updateInProgress.compareAndSet(false, true)) {
            log.info("Обновление уже выполняется");
            return;
        }

        FileChannel lockChannel = null;
        try {
            Path versionsFile = UpdateInstallerApplication.resolvePath(versionsPath);
            Path versionsDir = versionsFile.getParent();
            if (versionsDir != null && !Files.exists(versionsDir)) {
                Files.createDirectories(versionsDir);
            }
            Path lockPath = versionsFile.resolveSibling(".updater.lock");
            lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = lockChannel.tryLock();
            if (lock == null) {
                log.warn("Другой экземпляр updater'а уже выполняется — выходим");
                finish(2);
                return;
            }
        } catch (Exception e) {
            log.warn("Не удалось получить блокировку: {} — продолжаем без неё", e.getMessage());
        }

        try {
            runUpdate();
        } finally {
            // Не достигается при System.exit; ОС снимает блокировку при завершении.
            if (lockChannel != null) {
                try {
                    lockChannel.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void runUpdate() {
        try {
            gitHubDownloadService.setProgressCallback((progress, fileName) -> {
                ProgressDialog d = progressDialog;
                if (d != null) {
                    SwingUtilities.invokeLater(() -> {
                        d.setProgress(progress);
                        d.setMessage("Загрузка " + fileName + "... " + progress + "%");
                    });
                }
            });

            updateProgressMessage("Проверка обновлений...");

            CompletableFuture<String> jarV = CompletableFuture.supplyAsync(
                    () -> versionService.getRemoteFileVersion(jarUrl));
            CompletableFuture<String> ytV = CompletableFuture.supplyAsync(
                    () -> versionService.getRemoteFileVersion(ytDlpUrl));

            try {
                CompletableFuture.allOf(jarV, ytV).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Таймаут/ошибка при параллельном получении версий: {}", e.getMessage());
            }

            AppVersions remoteVersions = new AppVersions();
            remoteVersions.setJarVersion(jarV.getNow(VersionService.UNKNOWN_VERSION));
            remoteVersions.setYtDlpVersion(ytV.getNow(VersionService.UNKNOWN_VERSION));
            log.info("Удалённые версии: jar={}, yt-dlp={}",
                    remoteVersions.getJarVersion(), remoteVersions.getYtDlpVersion());

            updateProgressMessage("Проверка локальных версий...");
            AppVersions localVersions = versionService.loadLocalVersions();

            if (localVersions == null) {
                updateProgressMessage("Первая установка приложения...");
                log.info("Локальные версии не найдены, выполняется первая установка");

                boolean installed = downloadAndInstall(true, true);
                if (installed) {
                    versionService.saveLocalVersions(remoteVersions);
                    log.info("Первая установка выполнена успешно");
                    finish(0);
                } else {
                    log.error("Ошибка при первой установке");
                    finish(1);
                }
                return;
            }

            log.info("Локальные версии: jar={}, yt-dlp={}",
                    localVersions.getJarVersion(), localVersions.getYtDlpVersion());

            boolean jarUpdate = versionService.needsUpdate(
                    localVersions.getJarVersion(), remoteVersions.getJarVersion());
            boolean ytDlpUpdate = versionService.needsUpdate(
                    localVersions.getYtDlpVersion(), remoteVersions.getYtDlpVersion());

            if (jarUpdate || ytDlpUpdate) {
                updateProgressMessage("Обновление приложения...");
                log.info("Обнаружены новые версии: jar={}, yt-dlp={}", jarUpdate, ytDlpUpdate);

                if (downloadAndInstall(jarUpdate, ytDlpUpdate)) {
                    versionService.saveLocalVersions(remoteVersions);
                    log.info("Обновления установлены успешно");
                    finish(0);
                } else {
                    log.error("Не удалось установить обновления");
                    finish(1);
                }
                return;
            }

            updateProgressMessage("Обновления не требуются");
            log.info("Все файлы актуальны");
            localVersions.setLastChecked(remoteVersions.getLastChecked());
            versionService.saveLocalVersions(localVersions);
            finish(0);

        } catch (Exception e) {
            log.error("Ошибка при проверке обновлений: {}", e.getMessage(), e);
            updateProgressMessage("Ошибка: " + e.getMessage());
            finish(1);
        }
    }

    /** Скачивает все нужные файлы во временные, затем атомарно перемещает.
     * При падении любой загрузки — ни один целевой файл не тронут.
     */
    private boolean downloadAndInstall(boolean needJar, boolean needYtDlp) {
        Path jarFile = UpdateInstallerApplication.resolvePath(jarPath);
        Path ytDlpFile = UpdateInstallerApplication.resolvePath(ytDlpPath);

        Path jarTmp = jarFile.resolveSibling(jarFile.getFileName() + ".tmp");
        Path ytDlpTmp = ytDlpFile.resolveSibling(ytDlpFile.getFileName() + ".tmp");

        try {
            // Фаза 1: скачивание во временные файлы
            if (needJar) {
                updateProgressMessage("Загрузка yt-downloader.jar...");
                if (!gitHubDownloadService.downloadJarWithRetry(jarTmp)) {
                    log.error("Не удалось скачать yt-downloader.jar");
                    return false;
                }
            }
            if (needYtDlp) {
                updateProgressMessage("Загрузка yt-dlp.exe...");
                if (!gitHubDownloadService.downloadYtDlpWithRetry(ytDlpTmp)) {
                    log.error("Не удалось скачать yt-dlp.exe");
                    return false;
                }
            }

            // Фаза 2: атомарная замена
            if (needJar) {
                ensureParentDir(jarFile);
                Files.move(jarTmp, jarFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                log.info("yt-downloader.jar установлен");
            }
            if (needYtDlp) {
                ensureParentDir(ytDlpFile);
                Files.move(ytDlpTmp, ytDlpFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                log.info("yt-dlp.exe установлен");
            }
            return true;

        } catch (Exception e) {
            log.error("Ошибка установки: {}", e.getMessage(), e);
            try {
                Files.deleteIfExists(jarTmp);
                Files.deleteIfExists(ytDlpTmp);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    private static void ensureParentDir(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    private void updateProgressMessage(String message) {
        ProgressDialog d = progressDialog;
        if (d != null) {
            SwingUtilities.invokeLater(() -> d.setMessage(message));
        }
    }

    /** Порядок гарантирует, что EDT успевает выполнить dispose до убийства JVM */
    private void finish(int exitCode) {
        ProgressDialog d = progressDialog;
        if (d != null) {
            Runnable closeTask = d::close;
            if (SwingUtilities.isEventDispatchThread()) {
                closeTask.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(closeTask);
                } catch (Exception e) {
                    log.warn("Не удалось корректно закрыть диалог: {}", e.getMessage());
                }
            }
        }
        System.exit(exitCode);
    }
}