package com.example.ytdlp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
class FileSystemService {
    public List<String> getAvailableDrives() {
        List<String> drives = new ArrayList<>();

        try {
            // Для Windows
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                File[] roots = File.listRoots();
                for (File root : roots) {
                    drives.add(root.getAbsolutePath());
                }
            } else {
                // Для Linux/Mac
                drives.add("/");
                drives.add(System.getProperty("user.home"));
                drives.add("/tmp");
                drives.add("/home");
            }
        } catch (Exception e) {
            log.error("Error getting drives: {}", e.getMessage());
            drives.add(System.getProperty("user.home"));
        }

        return drives;
    }

    public List<YtDlpService.DirectoryItem> listDirectory(String path) throws IOException {
        List<YtDlpService.DirectoryItem> items = new ArrayList<>();
        File directory = new File(path);

        if (!directory.exists() || !directory.isDirectory()) {
            throw new IOException("Директория не существует: " + path);
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                YtDlpService.DirectoryItem item = new YtDlpService.DirectoryItem();
                item.setName(file.getName());
                item.setPath(file.getAbsolutePath());
                item.setDirectory(file.isDirectory());
                item.setSize(file.isFile() ? formatFileSize(file.length()) : "");
                item.setLastModified(new Date(file.lastModified()));

                items.add(item);
            }
        }

        // Сортируем: сначала папки, потом файлы
        items.sort((a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        return items;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}