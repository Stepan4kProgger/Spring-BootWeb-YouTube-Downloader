package com.example.ytdlp.controllers;

import com.example.ytdlp.config.ApplicationConfig; // Изменённый импорт
import com.example.ytdlp.model.DownloadProgress;
import com.example.ytdlp.model.DownloadRequest;
import com.example.ytdlp.model.DownloadResponse;
import com.example.ytdlp.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
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
    private ApplicationConfig appConfig; // Изменённый тип

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

    @PostMapping("/select-directory")
    public String selectDirectory(@RequestParam String directory,
                                  RedirectAttributes redirectAttributes) {
        // Сохраняем выбранную директорию
        if (appConfig.isRememberLastDirectory()) {
            appConfig.setDirectory(directory);
        }

        redirectAttributes.addFlashAttribute("selectedDirectory", directory);
        return "redirect:/yt-dlp/";
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

    @GetMapping("/downloads/check-file")
    @ResponseBody
    public ResponseEntity<String> checkFile(@RequestParam String filename,
                                            @RequestParam String directory) {
        try {
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            String decodedDirectory = URLDecoder.decode(directory, StandardCharsets.UTF_8);

            Path filePath = Paths.get(decodedDirectory).resolve(decodedFilename);
            File file = filePath.toFile();

            File dir = new File(decodedDirectory);
            String[] files = dir.list();

            return ResponseEntity.ok("File exists: " + file.exists() +
                    ", Directory exists: " + dir.exists() +
                    ", Files in directory: " + (files != null ? Arrays.toString(files) : "null"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/downloads/open-explorer-simple")
    public ResponseEntity<String> openFileInExplorerSimple(@RequestParam String filename,
                                                           @RequestParam String directory) {
        try {
            // Ручное декодирование
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            String decodedDirectory = URLDecoder.decode(directory, StandardCharsets.UTF_8);

            log.info("Opening file (simple): {} in {}", decodedFilename, decodedDirectory);

            Path filePath = Paths.get(decodedDirectory).resolve(decodedFilename);
            File file = filePath.toFile();

            if (!file.exists()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Файл не найден: " + decodedFilename);
            }

            // Просто запускаем проводник без сложных проверок
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                Runtime.getRuntime().exec("explorer /select,\"" + file.getAbsolutePath() + "\"");
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec("open -R \"" + file.getAbsolutePath() + "\"");
            } else {
                Runtime.getRuntime().exec("xdg-open \"" + file.getParent() + "\"");
            }

            return ResponseEntity.ok("Проводник открыт");

        } catch (Exception e) {
            log.error("Error in simple opener: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка: " + e.getMessage());
        }
    }

    @GetMapping("/downloads/open-file")
    public ResponseEntity<Resource> openFile(@RequestParam String filename,
                                             @RequestParam String directory) throws IOException {
        try {
            log.info("Opening file: {} in directory: {}", filename, directory);

            // Декодируем параметры
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            String decodedDirectory = URLDecoder.decode(directory, StandardCharsets.UTF_8);

            log.info("Decoded - filename: {}, directory: {}", decodedFilename, decodedDirectory);

            // Проверяем существование файла
            Path filePath = Paths.get(decodedDirectory).resolve(decodedFilename);
            File file = filePath.toFile();

            if (!file.exists()) {
                log.error("File not found: {}", filePath);
                return ResponseEntity.notFound().build();
            }

            // Открываем файл через системный проводник
            ytDlpService.openFileInExplorer(decodedFilename, decodedDirectory);

            // Также возвращаем файл для скачивания (опционально)
            Resource resource = new UrlResource(filePath.toUri());

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("Error opening file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/downloads/progress")
    @ResponseBody
    public List<DownloadProgress> getDownloadProgress() {
        return ytDlpService.getActiveDownloads();
    }

    @GetMapping("/downloads/file/{filename}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename,
                                                 @RequestParam String directory) throws IOException {
        return ytDlpService.downloadFile(filename, directory);
    }
}