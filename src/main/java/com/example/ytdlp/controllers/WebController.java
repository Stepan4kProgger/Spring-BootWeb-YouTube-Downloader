package com.example.ytdlp.controllers;

import com.example.ytdlp.model.AppConfig;
import com.example.ytdlp.model.DownloadRequest;
import com.example.ytdlp.model.DownloadResponse;
import com.example.ytdlp.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Controller
@RequestMapping("/yt-dlp")
@RequiredArgsConstructor
public class WebController {

    private final YtDlpService ytDlpService;

    @Autowired
    private AppConfig appConfig;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("version", ytDlpService.getVersion());
        return "index";
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

    @PostMapping("/download")
    public String downloadVideo(@RequestParam String url,
                                @RequestParam String downloadDirectory, // Теперь обязательный параметр
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
                redirectAttributes.addFlashAttribute("successMessage",
                        "Загрузка завершена успешно! Файл сохранен в: " + response.getOutputPath());
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

    @GetMapping("/downloads")
    public String downloadsList(@RequestParam(required = false) String directory,
                                Model model) {
        if (directory == null || directory.trim().isEmpty()) {
            model.addAttribute("error", "Пожалуйста, выберите директорию для просмотра");
            return "downloads";
        }

        try {
            List<YtDlpService.FileInfo> files = ytDlpService.getDownloadedFiles(directory);
            model.addAttribute("files", files);
            model.addAttribute("currentDirectory", directory);
        } catch (IOException e) {
            model.addAttribute("error", "Ошибка чтения директории: " + e.getMessage());
            model.addAttribute("currentDirectory", directory);
        }
        return "downloads";
    }

    @GetMapping("/downloads/file/{filename}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename,
                                                 @RequestParam String directory) throws IOException {
        return ytDlpService.downloadFile(filename, directory);
    }
}