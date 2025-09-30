package com.example.ytdlp.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.Executor;

@Slf4j
@Data
@Configuration
@EnableAsync
@ConfigurationProperties(prefix = "app.download")
public class ApplicationConfig {
    private String directory = "D:/downloads";
    private boolean clearHistoryOnStartup = false;
    private String quality = "worst";

    private static final String CONFIG_FILE = "yt-dlp-config.cfg";

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("YtDlp-");
        executor.initialize();
        return executor;
    }

    // Загрузка конфигурации из файла
    public void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream input = new FileInputStream(CONFIG_FILE)) {
            props.load(input);

            this.directory = props.getProperty("directory", "D:/downloads");
            this.clearHistoryOnStartup = Boolean.parseBoolean(
                    props.getProperty("clearHistoryOnStartup", "false"));
            this.quality = props.getProperty("quality", "worst");

        } catch (IOException e) {
            // Файл не существует, используем значения по умолчанию
            saveConfig(); // Создаем файл с значениями по умолчанию
        }
    }

    // Сохранение конфигурации в файл
    public void saveConfig() {
        Properties props = new Properties();
        props.setProperty("directory", this.directory);
        props.setProperty("clearHistoryOnStartup", String.valueOf(this.clearHistoryOnStartup));
        props.setProperty("quality", this.quality);

        try (FileOutputStream output = new FileOutputStream(CONFIG_FILE)) {
            props.store(output, "YT-DLP Configuration");
            log.info("Configuration saved successfully");
        } catch (IOException e) {
            throw new RuntimeException("Failed to save configuration", e);
        }
    }

    public void updateAllSettings(String directory,
                                  boolean clearHistoryOnStartup, String quality) {
        this.directory = directory;
        this.clearHistoryOnStartup = clearHistoryOnStartup;
        this.quality = quality;
        saveConfig();
    }
}