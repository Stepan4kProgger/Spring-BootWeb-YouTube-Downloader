package com.example.ytdlp.controllers;

import com.example.ytdlp.model.DownloadRequest;
import com.example.ytdlp.model.DownloadResponse;
import com.example.ytdlp.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.List;

@Slf4j
@Controller
@RequestMapping("/yt-dlp")
@RequiredArgsConstructor
public class WebController {

    private final YtDlpService ytDlpService;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("version", ytDlpService.getVersion());
        return "index";
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