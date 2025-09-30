package com.example.updater.service;

import com.example.updater.model.AppVersions;
import com.example.updater.utils.ProgressDialog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateService {

    private final SimpleGoogleDriveService googleDriveService;
    private final VersionService versionService;

    @Value("${app.update.local.jar-path}")
    private String jarPath;

    @Value("${app.update.local.yt-dlp-path}")
    private String ytDlpPath;

    private ProgressDialog progressDialog;

    private Thread progressUpdaterThread;

    @EventListener(ApplicationReadyEvent.class)
    public void checkForUpdatesOnStartup() {
        // Создаем диалог прогресса
        if (SwingUtilities.isEventDispatchThread()) {
            progressDialog = new ProgressDialog();
            progressDialog.setVisible(true);
        } else {
            SwingUtilities.invokeLater(() -> {
                progressDialog = new ProgressDialog();
                progressDialog.setVisible(true);
            });
        }

        // Запускаем проверку обновлений в отдельном потоке
        new Thread(this::performUpdateCheck).start();
    }

    private void startProgressUpdater(String fileName) {
        // Останавливаем предыдущий поток, если он существует
        if (progressUpdaterThread != null && progressUpdaterThread.isAlive()) {
            progressUpdaterThread.interrupt();
        }

        progressUpdaterThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int progress = googleDriveService.getCurrentProgress();
                    updateProgressMessage("Загрузка " + fileName + "... " + progress + "%");
                    setProgress(progress);

                    // Если загрузка завершена, прерываем поток
                    if (progress >= 100) {
                        break;
                    }

                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        progressUpdaterThread.setDaemon(true);
        progressUpdaterThread.start();
    }

    private void setProgress(int progress) {
        if (progressDialog != null) {
            SwingUtilities.invokeLater(() -> progressDialog.setProgress(progress));
        }
    }

    private void performUpdateCheck() {
        log.info("Запуск проверки обновлений при старте приложения...");

        try {
            // Устанавливаем диалог прогресса в сервис загрузки
            googleDriveService.setProgressDialog(progressDialog);

            // Обновляем сообщение в диалоге
            updateProgressMessage("Проверка обновлений...");

            // Загружаем удаленные версии
            updateProgressMessage("Загрузка информации о версиях...");
            String versionsContent = googleDriveService.downloadVersionsFile();
            if (versionsContent == null) {
                updateProgressMessage("Ошибка загрузки информации о версиях");
                log.error("Не удалось загрузить файл версий с Google Drive");
                closeProgressDialogAfterDelay();
                System.exit(0);
                return;
            }

            updateProgressMessage("Анализ версий...");
            AppVersions remoteVersions = versionService.parseRemoteVersions(versionsContent);
            if (remoteVersions == null) {
                updateProgressMessage("Ошибка анализа версий");
                log.error("Не удалось распарсить удаленные версии");
                closeProgressDialogAfterDelay();
                System.exit(0);
                return;
            }

            // Загружаем локальные версии
            updateProgressMessage("Проверка локальных версий...");
            AppVersions localVersions = versionService.loadLocalVersions();
            if (localVersions == null) {
                updateProgressMessage("Первая установка приложения...");
                log.info("Локальные версии не найдены, требуется первая установка");

                if (installAllFiles(remoteVersions)) {
                    versionService.saveLocalVersions(remoteVersions);
                    updateProgressMessage("Установка завершена успешно!");
                    log.info("Первая установка выполнена успешно!");
                } else {
                    updateProgressMessage("Ошибка установки");
                    log.error("Ошибка при первой установке");
                    closeProgressDialogAfterDelay();
                    System.exit(0);
                }
            } else {
                log.info("Локальные версии: jar={}, yt-dlp={}",
                        localVersions.getJarVersion(), localVersions.getYtDlpVersion());

                // Проверяем наличие обновлений
                if (versionService.isNewVersionAvailable(localVersions, remoteVersions)) {
                    updateProgressMessage("Обновление приложения...");
                    log.info("Обнаружены новые версии, начинаем обновление...");

                    boolean updated = updateNeededFiles(localVersions, remoteVersions);

                    if (updated) {
                        versionService.saveLocalVersions(remoteVersions);
                        updateProgressMessage("Обновление завершено успешно!");
                        log.info("Обновления установлены успешно!");
                    } else {
                        updateProgressMessage("Ошибка обновления");
                        log.error("Не удалось установить обновления");
                        closeProgressDialogAfterDelay();
                        System.exit(0);
                    }
                } else {
                    updateProgressMessage("Обновления не требуются");
                    log.info("Обновлений не требуется. Все файлы актуальны.");
                    closeProgressDialogAfterDelay();
                    System.exit(0);
                }
            }

            // Закрываем диалог с задержкой, чтобы пользователь увидел сообщение
            closeProgressDialogAfterDelay();

        } catch (Exception e) {
            updateProgressMessage("Ошибка: " + e.getMessage());
            log.error("Ошибка при проверке обновлений: {}", e.getMessage(), e);
            closeProgressDialogAfterDelay();
            System.exit(0);
        }
    }

    private boolean installAllFiles(AppVersions remoteVersions) {
        log.info("Выполняется первая установка всех файлов...");
        updateProgressMessage("Установка приложения...");

        boolean jarSuccess = updateFile(Path.of(jarPath), googleDriveService.getJarFileId(), "yt-downloader.jar");
        boolean exeSuccess = updateFile(Path.of(ytDlpPath), googleDriveService.getYtDlpFileId(), "yt-dlp.exe");

        return jarSuccess && exeSuccess;
    }

    private boolean updateNeededFiles(AppVersions local, AppVersions remote) {
        boolean updated = false;

        // Обновляем jar если нужно
        if (versionService.compareVersions(local.getJarVersion(), remote.getJarVersion()) < 0) {
            updateProgressMessage("Обновление основного приложения...");
            log.info("Обновление yt-downloader.jar...");
            if (updateFile(Path.of(jarPath), googleDriveService.getJarFileId(), "yt-downloader.jar")) {
                updated = true;

                // Добавляем задержку после успешной загрузки первого файла
                try {
                    log.info("Добавляем задержку в 1 секунду перед загрузкой следующего файла...");
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.warn("Задержка прервана, продолжаем без задержки");
                }
            } else {
                log.error("Не удалось обновить yt-downloader.jar");
                return false;
            }
        }

        // Обновляем yt-dlp если нужно
        if (versionService.compareVersions(local.getYtDlpVersion(), remote.getYtDlpVersion()) < 0) {
            updateProgressMessage("Обновление yt-dlp...");
            log.info("Обновление yt-dlp.exe...");
            if (updateFile(Path.of(ytDlpPath), googleDriveService.getYtDlpFileId(), "yt-dlp.exe")) {
                updated = true;
            } else {
                log.error("Не удалось обновить yt-dlp.exe");
                return false;
            }
        }

        return updated;
    }

    private boolean updateFile(Path localFile, String fileId, String fileName) {
        try {
            // Создание временного файла в той же папке
            Path tempFile = localFile.getParent().resolve(fileName + ".tmp");
            googleDriveService.setCurrentProgress(0);
            startProgressUpdater(fileName);

            // Загрузка новой версии
            updateProgressMessage("Загрузка " + fileName + "...");
            boolean downloadSuccess = googleDriveService.downloadFileWithRetry(fileId, tempFile, fileName);

            if (downloadSuccess) {
                // Удаляем старый файл если существует
                if (Files.exists(localFile)) {
                    Files.delete(localFile);
                }

                // Переименовываем временный файл в целевой
                Files.move(tempFile, localFile);
                updateProgressMessage(fileName + " успешно обновлен");
                log.info("Файл {} успешно обновлен", fileName);
                return true;
            } else {
                // Удаление временного файла в случае ошибки
                Files.deleteIfExists(tempFile);
                updateProgressMessage("Ошибка загрузки " + fileName);
                log.error("Не удалось загрузить файл: {}", fileName);
                return false;
            }
        } catch (Exception e) {
            updateProgressMessage("Ошибка обновления " + fileName);
            log.error("Ошибка при обновлении файла {}: {}", fileName, e.getMessage(), e);
            return false;
        }
    }

    private void updateProgressMessage(String message) {
        if (progressDialog != null) {
            SwingUtilities.invokeLater(() -> progressDialog.setMessage(message));
        }
    }

    private void closeProgressDialogAfterDelay() {
        if (progressDialog != null) {
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    log.warn("Задержка перед закрытием диалога прервана");
                }
                SwingUtilities.invokeLater(() -> progressDialog.close());
            }).start();
        }
    }
}