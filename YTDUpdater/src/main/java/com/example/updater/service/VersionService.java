package com.example.updater.service;

import com.example.updater.UpdateInstallerApplication;
import com.example.updater.model.AppVersions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class VersionService {

    /** Маркер «версия неизвестна» — не совпадает ни с одной реальной версией. */
    public static final String UNKNOWN_VERSION = "unknown";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** URL вида: https://github.com/{owner}/{repo}/releases/latest/download/{file} */
    private static final Pattern GITHUB_RELEASE_URL_PATTERN = Pattern.compile(
            "https?://github\\.com/([^/]+)/([^/]+)/releases/(?:latest/)?download/");

    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Value("${app.update.local.versions-path}")
    private String versionsPath;

    public AppVersions loadLocalVersions() {
        Path configFile = UpdateInstallerApplication.resolvePath(versionsPath);
        if (!Files.exists(configFile)) {
            log.info("Файл версий не найден: {}", configFile);
            return null;
        }

        AppVersions versions = new AppVersions();
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(configFile.toFile())) {
            props.load(input);

            versions.setJarVersion(props.getProperty("jar.version"));
            versions.setYtDlpVersion(props.getProperty("ytdlp.version"));

            String lastCheckedStr = props.getProperty("last.checked");
            if (lastCheckedStr != null) {
                versions.setLastChecked(LocalDateTime.parse(lastCheckedStr, FORMATTER));
            }

            if (versions.getJarVersion() != null && versions.getYtDlpVersion() != null) {
                log.info("Загружены локальные версии: jar={}, yt-dlp={}, проверено={}",
                        versions.getJarVersion(), versions.getYtDlpVersion(),
                        versions.getLastChecked());
                return versions;
            }
            log.warn("Файл версий поврежден или содержит неполные данные");
            return null;
        } catch (IOException e) {
            log.warn("Не удалось загрузить файл версий: {}", e.getMessage());
            return null;
        }
    }

    public void saveLocalVersions(AppVersions versions) {
        if (versions == null) {
            log.warn("Попытка сохранить пустые версии");
            return;
        }

        Properties props = new Properties();
        // Явно записываем маркер, чтобы отличие «файл отсутствует» от «файл есть, но версия неизвестна»
        // сохранялось при повторной загрузке.
        props.setProperty("jar.version",
                versions.getJarVersion() != null ? versions.getJarVersion() : UNKNOWN_VERSION);
        props.setProperty("ytdlp.version",
                versions.getYtDlpVersion() != null ? versions.getYtDlpVersion() : UNKNOWN_VERSION);
        LocalDateTime checked = versions.getLastChecked() != null
                ? versions.getLastChecked() : LocalDateTime.now();
        props.setProperty("last.checked", checked.format(FORMATTER));

        try {
            Path configFile = UpdateInstallerApplication.resolvePath(versionsPath);
            Path parentDir = configFile.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Создана директория для конфигурации: {}", parentDir.toAbsolutePath());
            }

            Path tmpFile = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            try (OutputStream output = new FileOutputStream(tmpFile.toFile())) {
                props.store(output, "Application versions configuration");
            }
            try {
                Files.move(tmpFile, configFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmpFile, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Версии сохранены: jar={}, yt-dlp={}, время={}",
                    versions.getJarVersion(), versions.getYtDlpVersion(), checked);
        } catch (IOException e) {
            log.error("Ошибка при сохранении версий: {}", e.getMessage(), e);
        }
    }

    /** Возвращает версию файла по GitHub API (/repos/{owner}/{repo}/releases/latest).*/
    public String getRemoteFileVersion(String fileUrl) {
        Matcher m = GITHUB_RELEASE_URL_PATTERN.matcher(fileUrl);
        if (!m.find()) {
            log.warn("Не удалось извлечь owner/repo из URL: {}", fileUrl);
            return UNKNOWN_VERSION;
        }
        String owner = m.group(1);
        String repo = m.group(2);
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "updater")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(20, TimeUnit.SECONDS)
                    .get();

            if (response.statusCode() != 200) {
                log.warn("GitHub API вернул {} для {}", response.statusCode(), apiUrl);
                return UNKNOWN_VERSION;
            }

            String body = response.body();
            String tag = extractJsonString(body, "tag_name");
            String publishedAt = extractJsonString(body, "published_at");
            if (publishedAt == null) {
                log.warn("В ответе GitHub API отсутствует published_at для {}", apiUrl);
                return UNKNOWN_VERSION;
            }

            String date = Instant.parse(publishedAt)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString();
            String version = (tag != null ? tag : "release") + "@" + date;
            log.info("Версия {}: {}", fileUrl, version);
            return version;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Запрос версии прерван: {}", fileUrl);
            return UNKNOWN_VERSION;
        } catch (Exception e) {
            log.warn("Не удалось получить версию для {}: {}", fileUrl, e.getMessage());
            return UNKNOWN_VERSION;
        }
    }

    private static String extractJsonString(String json, String field) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public boolean needsUpdate(String localVersion, String remoteVersion) {
        if (remoteVersion == null || UNKNOWN_VERSION.equals(remoteVersion)) {
            // Не знаем удалённую версию — не трогаем локальный файл.
            return false;
        }
        if (localVersion == null || UNKNOWN_VERSION.equals(localVersion)) {
            // Локальная версия неизвестна/отсутствует — нужно установить/обновить.
            return true;
        }
        return !localVersion.equals(remoteVersion);
    }
}