package com.example.ytdlp.controller;

import com.example.ytdlp.config.ApplicationConfig;
import com.example.ytdlp.utils.model.DownloadProgress;
import com.example.ytdlp.utils.model.DownloadRequest;
import com.example.ytdlp.utils.model.DownloadResponse;
import com.example.ytdlp.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Controller
@RequestMapping("/yt-dlp")
@RequiredArgsConstructor
public class WebController {
    private final YtDlpService ytDlpService;
    private final ApplicationConfig appConfig;

    private static final Map<String, Map<String, String>> ACTION_MESSAGES = Map.of(
            "pause", Map.of("success", "Загрузка приостановлена", "error", "Не удалось приостановить загрузку"),
            "resume", Map.of("success", "Загрузка возобновлена", "error", "Не удалось возобновить загрузку"),
            "cancel", Map.of("success", "Загрузка отменена", "error", "Не удалось отменить загрузку"),
            "delete", Map.of("success", "Файл удален", "error", "Не удалось удалить файл")
    );

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("ytdlpVersion", "Загрузка...");
        model.addAttribute("ffmpegVersion", "Загрузка..."); // Добавить эту строку
        model.addAttribute("selectedDirectory", appConfig.getDirectory());
        model.addAttribute("activeDownloads", ytDlpService.getActiveDownloads());
        model.addAttribute("downloadHistory", ytDlpService.getDownloadHistory());
        return "index";
    }

    @GetMapping("/ytdlp-version")
    @ResponseBody
    public String getYtDlpVersion() {
        return ytDlpService.getYtDlpVersion();
    }

    @GetMapping("/ffmpeg-version")
    @ResponseBody
    public String getFfmpegVersion() {
        return ytDlpService.getFfmpegVersion();
    }

    @GetMapping("/browse")
    public String browseDirectory(@RequestParam(required = false) String path, Model model) {
        try {
            if (path == null || path.trim().isEmpty()) {
                model.addAttribute("drives", ytDlpService.getAvailableDrives());
                model.addAttribute("currentPath", "");
                return "browser";
            }

            String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
            model.addAttribute("items", ytDlpService.listDirectory(decodedPath));
            model.addAttribute("currentPath", decodedPath);

            File currentDir = new File(decodedPath);
            File parentDir = currentDir.getParentFile();
            if (parentDir != null && parentDir.exists()) {
                model.addAttribute("parentPath", parentDir.getAbsolutePath());
            }
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            if (path != null) {
                try {
                    model.addAttribute("currentPath", URLDecoder.decode(path, StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    model.addAttribute("currentPath", path);
                }
            }
        }
        return "browser";
    }

    @GetMapping("/get-current-directory")
    @ResponseBody
    public String getCurrentDirectory() {
        return appConfig.getDirectory();
    }

    @GetMapping("/downloads/all")
    @ResponseBody
    public List<DownloadProgress> getAllDownloads() {
        List<DownloadProgress> allDownloads = new ArrayList<>();

        // Добавляем активные загрузки
        allDownloads.addAll(ytDlpService.getActiveDownloads().stream()
                .map(DownloadProgress::new).toList());

        // Добавляем историю загрузок (теперь там только завершенные/ошибки/отмененные)
        allDownloads.addAll(ytDlpService.getDownloadHistory().stream()
                .map(DownloadProgress::new).toList());

        // Сортируем: активные сверху, затем по времени завершения
        allDownloads.sort((a, b) -> {
            boolean aIsActive = isActiveStatus(a.getStatus());
            boolean bIsActive = isActiveStatus(b.getStatus());

            if (aIsActive && !bIsActive) return -1;
            if (!aIsActive && bIsActive) return 1;

            LocalDateTime aTime = a.getEndTime() != null ? a.getEndTime() : a.getStartTime();
            LocalDateTime bTime = b.getEndTime() != null ? b.getEndTime() : b.getStartTime();

            if (aTime == null) return 1;
            if (bTime == null) return -1;
            return bTime.compareTo(aTime);
        });

        return allDownloads;
    }

    private boolean isActiveStatus(String status) {
        return status != null && (status.equals("downloading") || status.equals("paused"));
    }

    @PostMapping("/downloads/{action}")
    @ResponseBody
    public ResponseEntity<String> handleDownloadAction(
            @PathVariable String action,
            @RequestParam String downloadId) {
        try {
            boolean success = switch (action) {
                case "pause" -> ytDlpService.pauseDownload(downloadId);
                case "resume" -> ytDlpService.resumeDownload(downloadId);
                case "cancel" -> ytDlpService.cancelDownload(downloadId);
                case "delete" -> ytDlpService.deleteDownloadedFile(downloadId);
                default -> false;
            };

            Map<String, String> messages = ACTION_MESSAGES.getOrDefault(action,
                    Map.of("success", "Операция выполнена", "error", "Ошибка выполнения операции"));

            if (success) {
                return ResponseEntity.ok(messages.get("success"));
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(messages.get("error"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка операции: " + e.getMessage());
        }
    }

    @PostMapping("/clear-downloads")
    @ResponseBody
    public ResponseEntity<String> clearDownloads() {
        try {
            ytDlpService.clearDownloadHistory();
            return ResponseEntity.ok("История загрузок очищена");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка очистки истории: " + e.getMessage());
        }
    }

    @PostMapping("/download")
    public String downloadVideo(DownloadRequest request, RedirectAttributes redirectAttributes) {
        if (request.getDownloadDirectory() == null || request.getDownloadDirectory().trim().isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Ошибка: Необходимо выбрать папку для загрузки");
            return "redirect:/yt-dlp/";
        }

        try {
            DownloadResponse response = ytDlpService.downloadVideo(request);
            if (response.isSuccess()) {
                redirectAttributes.addFlashAttribute("successMessage",
                        "Загрузка завершена успешно! Файл: " + response.getFilename());
            } else {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Ошибка загрузки: " + response.getError());
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Ошибка: " + e.getMessage());
        }

        return "redirect:/";
    }

    @PostMapping("/downloads/open-explorer")
    @ResponseBody
    public ResponseEntity<String> openFileInExplorer(
            @RequestParam String filename,
            @RequestParam String directory) {
        try {
            ytDlpService.openFileInExplorer(
                    URLDecoder.decode(filename, StandardCharsets.UTF_8),
                    URLDecoder.decode(directory, StandardCharsets.UTF_8)
            );

            // Декодируем имя файла для красивого отображения
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            return ResponseEntity.ok("Файл открыт в проводнике: " + decodedFilename);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка открытия проводника: " + e.getMessage());
        }
    }

    @GetMapping("/processes/info")
    @ResponseBody
    public Map<String, String> getProcessesInfo() {
        return ytDlpService.getActiveProcessesInfo();
    }

    @PostMapping("/shutdown")
    @ResponseBody
    public ResponseEntity<String> gracefulShutdown() {
        try {
            ytDlpService.stopAllDownloads();
            return ResponseEntity.ok("All downloads stopped. Application can be safely closed.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error during shutdown: " + e.getMessage());
        }
    }

    @PostMapping("/settings/save-all")
    @ResponseBody
    public ResponseEntity<String> saveAllSettings(
            @RequestParam String directory,
            @RequestParam boolean clearHistoryOnStartup,
            @RequestParam String quality) {

        try {
            // Используем новый метод для массового обновления
            appConfig.updateAllSettings(directory, clearHistoryOnStartup, quality);
            return ResponseEntity.ok("Все настройки сохранены");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка сохранения настроек: " + e.getMessage());
        }
    }

    @GetMapping("/settings")
    @ResponseBody
    public ResponseEntity<Map<String, String>> getSettings() {
        try {
            Map<String, String> settings = new HashMap<>();
            settings.put("directory", appConfig.getDirectory());
            settings.put("clearHistoryOnStartup", String.valueOf(appConfig.isClearHistoryOnStartup()));
            settings.put("quality", appConfig.getQuality());

            return ResponseEntity.ok(settings);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}