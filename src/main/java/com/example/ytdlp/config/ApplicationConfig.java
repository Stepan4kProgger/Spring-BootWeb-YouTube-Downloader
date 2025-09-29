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
    private boolean rememberLastDirectory = true;
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

    public void setDirectory(String directory) {
        this.directory = directory.replace("/", File.separator)
                .replace("\\", File.separator);
    }

    // Загрузка конфигурации из файла
    public void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream input = new FileInputStream(CONFIG_FILE)) {
            props.load(input);

            this.directory = props.getProperty("directory", "D:/downloads");
            this.rememberLastDirectory = Boolean.parseBoolean(
                    props.getProperty("rememberLastDirectory", "true"));
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
        props.setProperty("rememberLastDirectory", String.valueOf(this.rememberLastDirectory));
        props.setProperty("clearHistoryOnStartup", String.valueOf(this.clearHistoryOnStartup));
        props.setProperty("quality", this.quality);

        try (FileOutputStream output = new FileOutputStream(CONFIG_FILE)) {
            props.store(output, "YT-DLP Configuration");
            log.info("Configuration saved successfully");
        } catch (IOException e) {
            throw new RuntimeException("Failed to save configuration", e);
        }
    }

    // Обновление конкретной настройки
    public void updateConfig(String key, String value) {
        Properties props = new Properties();
        try (FileInputStream input = new FileInputStream(CONFIG_FILE)) {
            props.load(input);
        } catch (IOException e) {
            // Файл не существует, продолжаем
        }

        props.setProperty(key, value);

        try (FileOutputStream output = new FileOutputStream(CONFIG_FILE)) {
            props.store(output, "YT-DLP Configuration");
        } catch (IOException e) {
            throw new RuntimeException("Failed to update configuration", e);
        }

        // Обновляем значение в памяти
        switch (key) {
            case "directory":
                this.directory = value;
                break;
            case "rememberLastDirectory":
                this.rememberLastDirectory = Boolean.parseBoolean(value);
                break;
            case "clearHistoryOnStartup":
                this.clearHistoryOnStartup = Boolean.parseBoolean(value);
                break;
            case "quality":
                this.quality = value;
                break;
        }
    }
}