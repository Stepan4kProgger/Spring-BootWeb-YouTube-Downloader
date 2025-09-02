package com.example.ytdlp.controller;

import com.example.ytdlp.config.ApplicationConfig; // Изменённый импорт
import com.example.ytdlp.model.DownloadProgress;
import com.example.ytdlp.model.DownloadRequest;
import com.example.ytdlp.model.DownloadResponse;
import com.example.ytdlp.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Controller
@RequestMapping("/yt-dlp")
@RequiredArgsConstructor
public class WebController {

    @Autowired
    private YtDlpService ytDlpService;

    @Autowired
    private ApplicationConfig appConfig;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("version", "Загрузка...");

        List<DownloadProgress> activeDownloads = ytDlpService.getActiveDownloads();
        List<DownloadProgress> downloadHistory = ytDlpService.getDownloadHistory();

        // Логируем для отладки
        log.info("Active downloads: {}", activeDownloads.size());
        log.info("Download history: {}", downloadHistory.size());

        if (!downloadHistory.isEmpty()) {
            log.info("History sample: {} - {}",
                    downloadHistory.get(0).getFilename(),
                    downloadHistory.get(0).getStatus());
        }

        model.addAttribute("activeDownloads", activeDownloads);
        model.addAttribute("downloadHistory", downloadHistory);

        return "index";
    }

    @GetMapping("/version")
    @ResponseBody
    public String getVersion() {
        return ytDlpService.getVersion();
    }

    @GetMapping("/browse")
    public String browseDirectory(@RequestParam(required = false) String path, Model model) {
        try {
            if (path == null || path.trim().isEmpty()) {
                // Корневой уровень - показываем диски
                List<String> drives = ytDlpService.getAvailableDrives();
                model.addAttribute("drives", drives);
                model.addAttribute("currentPath", "");
            } else {
                // Декодируем путь
                String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);

                // Проверяем существование директории
                File directory = new File(decodedPath);
                if (!directory.exists() || !directory.isDirectory()) {
                    throw new IOException("Директория не существует: " + decodedPath);
                }

                // Показываем содержимое директории
                List<YtDlpService.DirectoryItem> items = ytDlpService.listDirectory(decodedPath);
                model.addAttribute("items", items);
                model.addAttribute("currentPath", decodedPath);

                // Получаем родительскую директорию и кодируем для data-атрибута
                String parentPath = directory.getParent();
                if (parentPath != null) {
                    model.addAttribute("parentPath", parentPath); // Просто путь, без кодирования
                }
            }
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            if (path != null) {
                try {
                    String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
                    model.addAttribute("currentPath", decodedPath);
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
        allDownloads.addAll(ytDlpService.getActiveDownloads());
        allDownloads.addAll(ytDlpService.getDownloadHistory());

        // Сортируем по дате
        allDownloads.sort((a, b) -> {
            LocalDateTime aTime = a.getEndTime() != null ? a.getEndTime() : a.getStartTime();
            LocalDateTime bTime = b.getEndTime() != null ? b.getEndTime() : b.getStartTime();

            if (aTime == null) return 1;
            if (bTime == null) return -1;
            return bTime.compareTo(aTime);
        });

        return allDownloads;
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
    public String downloadVideo(@RequestParam String url,
                                @RequestParam String downloadDirectory,
                                @RequestParam(required = false) String format,
                                @RequestParam(required = false) String outputTemplate,
                                @RequestParam(required = false) Boolean extractAudio,
                                @RequestParam(required = false) String audioFormat,
                                @RequestParam(required = false) Integer quality,
                                RedirectAttributes redirectAttributes) {

        // Проверка на пустую директорию
        if (downloadDirectory == null || downloadDirectory.trim().isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Ошибка: Необходимо выбрать папку для загрузки");
            return "redirect:/yt-dlp/";
        }

        // Создаем объект запроса
        DownloadRequest request = new DownloadRequest();
        request.setUrl(url);
        request.setDownloadDirectory(downloadDirectory); // Обязательная директория
        request.setFormat(format);
        request.setExtractAudio(extractAudio);
        request.setAudioFormat(audioFormat);
        request.setQuality(quality);

        try {
            DownloadResponse response = ytDlpService.downloadVideo(request);

            if (response.isSuccess()) {
                String successMessage = "Загрузка завершена успешно!";
                if (response.getFilename() != null && !response.getFilename().equals("unknown")) {
                    successMessage += " Файл: " + response.getFilename();
                }
                successMessage += " Путь: " + response.getOutputPath();

                redirectAttributes.addFlashAttribute("successMessage", successMessage);
            } else {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Ошибка загрузки: " + response.getError());
            }

        } catch (Exception e) {
            log.error("Error during download: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Ошибка: " + e.getMessage());
        }

        return "redirect:/";
    }

    @PostMapping("/downloads/open-explorer")
    @ResponseBody
    public ResponseEntity<String> openFileInExplorer(@RequestParam String filename,
                                                     @RequestParam String directory) {
        try {
            // Ручное декодирование параметров
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            String decodedDirectory = URLDecoder.decode(directory, StandardCharsets.UTF_8);

            log.info("Opening file in explorer: {} in {}", decodedFilename, decodedDirectory);

            // Проверяем существование файла
            Path filePath = Paths.get(decodedDirectory).resolve(decodedFilename);
            File file = filePath.toFile();

            if (!file.exists()) {
                log.error("File not found: {}", filePath);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Файл не существует: " + decodedFilename);
            }

            // Открываем проводник
            ytDlpService.openFileInExplorer(decodedFilename, decodedDirectory);
            return ResponseEntity.ok("Проводник открыт с файлом: " + decodedFilename);

        } catch (IOException e) {
            log.error("Error opening file in explorer: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка открытия проводника: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error opening file in explorer: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Неожиданная ошибка: " + e.getMessage());
        }
    }
}