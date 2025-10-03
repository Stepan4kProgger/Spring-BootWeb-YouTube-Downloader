package com.example.ytdlp.service;

import com.example.ytdlp.utils.model.DownloadProgress;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
class HistoryService {
    private static final String HISTORY_FILE = "download_history.json";
    private final List<DownloadProgress> downloadHistory = Collections.synchronizedList(new ArrayList<>());

    public void loadDownloadHistory() {
        Path historyPath = Paths.get(HISTORY_FILE);

        if (!Files.exists(historyPath)) {
            log.info("No download history file found, creating empty history");
            saveDownloadHistory();
            return;
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            List<DownloadProgress> loadedHistory = objectMapper.readValue(
                    historyPath.toFile(),
                    new TypeReference<>() {}
            );

            downloadHistory.clear();
            downloadHistory.addAll(loadedHistory);
            log.info("Loaded {} items from download history", downloadHistory.size());

        } catch (Exception e) {
            log.error("Error loading download history from JSON format. Creating empty history.", e);
            downloadHistory.clear();
            saveDownloadHistory();
        }
    }

    public void saveDownloadHistory() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

            List<DownloadProgress> historyForSave = downloadHistory.stream()
                    .map(DownloadProgress::new)
                    .collect(Collectors.toList());

            String json = objectMapper.writeValueAsString(historyForSave);
            Path historyPath = Paths.get(HISTORY_FILE);
            Files.writeString(historyPath, json);

            log.info("Saved {} items to download history file: {}",
                    historyForSave.size(), historyPath.toAbsolutePath());

        } catch (IOException e) {
            log.error("Error saving download history to file: {}", e.getMessage(), e);
        }
    }

    public void addToDownloadHistory(DownloadProgress progress) {
        downloadHistory.add(progress);
        saveDownloadHistory();
    }

    public List<DownloadProgress> getDownloadHistory() {
        return downloadHistory.stream()
                .sorted((a, b) -> {
                    LocalDateTime aTime = a.getEndTime() != null ? a.getEndTime() : a.getStartTime();
                    LocalDateTime bTime = b.getEndTime() != null ? b.getEndTime() : b.getStartTime();

                    if (aTime == null) return 1;
                    if (bTime == null) return -1;
                    return bTime.compareTo(aTime); // новые выше старых
                })
                .collect(Collectors.toList());
    }

    public void clearDownloadHistory() {
        downloadHistory.clear();
        saveDownloadHistory();
        log.info("Download history cleared");
    }
}