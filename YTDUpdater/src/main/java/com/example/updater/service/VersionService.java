package com.example.updater.service;

import com.example.updater.model.AppVersions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Slf4j
@Service
public class VersionService {

    @Value("${app.update.local.versions-path}")
    private String versionsPath;

    public AppVersions loadLocalVersions() {
        AppVersions versions = new AppVersions();

        Path configFile = Path.of(versionsPath);
        if (Files.exists(configFile)) {
            Properties props = new Properties();
            try (InputStream input = new FileInputStream(configFile.toFile())) {
                props.load(input);
                versions.setJarVersion(props.getProperty("jar.version"));
                versions.setYtDlpVersion(props.getProperty("ytdlp.version"));

                if (versions.getJarVersion() != null && versions.getYtDlpVersion() != null) {
                    log.info("Загружены локальные версии: jar={}, yt-dlp={}",
                            versions.getJarVersion(), versions.getYtDlpVersion());
                } else {
                    log.warn("Файл версий поврежден или содержит неполные данные");
                    return null;
                }
            } catch (IOException e) {
                log.warn("Не удалось загрузить файл версий: {}", e.getMessage());
                return null;
            }
        } else {
            log.info("Файл версий не найден");
            return null;
        }

        return versions;
    }

    public void saveLocalVersions(AppVersions versions) {
        if (versions == null || versions.getJarVersion() == null || versions.getYtDlpVersion() == null) {
            log.warn("Попытка сохранить неполные версии");
            return;
        }

        Properties props = new Properties();
        props.setProperty("jar.version", versions.getJarVersion());
        props.setProperty("ytdlp.version", versions.getYtDlpVersion());

        try {
            // Создаем директорию если не существует
            Path configFile = Path.of(versionsPath);
            Path parentDir = configFile.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            try (OutputStream output = new FileOutputStream(versionsPath)) {
                props.store(output, "Application versions configuration");
                log.info("Версии сохранены: jar={}, yt-dlp={}",
                        versions.getJarVersion(), versions.getYtDlpVersion());
            }
        } catch (IOException e) {
            log.error("Ошибка при сохранении версий: {}", e.getMessage(), e);
        }
    }

    public AppVersions parseRemoteVersions(String content) {
        AppVersions versions = new AppVersions();

        if (content == null || content.trim().isEmpty()) {
            log.error("Передан пустой контент для парсинга версий");
            return null;
        }

        Properties props = new Properties();
        try (StringReader reader = new StringReader(content)) {
            props.load(reader);

            String jarVersion = props.getProperty("jar.version");
            String ytDlpVersion = props.getProperty("ytdlp.version");

            if (jarVersion == null || ytDlpVersion == null) {
                log.error("Удаленный файл версий не содержит необходимых данных");
                return null;
            }

            versions.setJarVersion(jarVersion);
            versions.setYtDlpVersion(ytDlpVersion);

            log.info("Загружены удаленные версии: jar={}, yt-dlp={}",
                    versions.getJarVersion(), versions.getYtDlpVersion());

            return versions;

        } catch (IOException e) {
            log.error("Не удалось распарсить удаленные версии: {}", e.getMessage());
            return null;
        }
    }

    public boolean isNewVersionAvailable(AppVersions local, AppVersions remote) {
        if (local == null || remote == null) {
            log.error("Неверные параметры для проверки версий");
            return false;
        }

        boolean jarUpdate = compareVersions(local.getJarVersion(), remote.getJarVersion()) < 0;
        boolean ytDlpUpdate = compareVersions(local.getYtDlpVersion(), remote.getYtDlpVersion()) < 0;

        if (jarUpdate) {
            log.info("Доступна новая версия jar: {} -> {}", local.getJarVersion(), remote.getJarVersion());
        }
        if (ytDlpUpdate) {
            log.info("Доступна новая версия yt-dlp: {} -> {}", local.getYtDlpVersion(), remote.getYtDlpVersion());
        }

        return jarUpdate || ytDlpUpdate;
    }

    public int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) {
            return 0;
        }

        try {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");

            int length = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < length; i++) {
                int num1 = (i < parts1.length) ? Integer.parseInt(parts1[i]) : 0;
                int num2 = (i < parts2.length) ? Integer.parseInt(parts2[i]) : 0;

                if (num1 != num2) {
                    return Integer.compare(num1, num2);
                }
            }
            return 0;
        } catch (NumberFormatException e) {
            log.warn("Ошибка при сравнении версий: {} и {}", v1, v2);
            return 0;
        }
    }
}