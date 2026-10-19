package com.example.ytdlp.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

@Slf4j
@Data
@Component
public class ApplicationConfig {
    private String directory = "D:/downloads";
    private boolean clearHistoryOnStartup = false;
    private String quality = "worst";
    private boolean compatibilityMode = true;

    private static final String CONFIG_FILE = "yt-dlp-config.cfg";

    public void loadConfig() {
        Properties props = new Properties();
        try (FileInputStream input = new FileInputStream(CONFIG_FILE)) {
            props.load(input);
            this.directory = props.getProperty("directory", "D:/downloads");
            this.clearHistoryOnStartup = Boolean.parseBoolean(
                    props.getProperty("clearHistoryOnStartup", "false"));
            this.quality = props.getProperty("quality", "worst");
            this.compatibilityMode = Boolean.parseBoolean(
                    props.getProperty("compatibilityMode", "true"));
        } catch (IOException e) {
            saveConfig();
        }
    }

    public void saveConfig() {
        Properties props = new Properties();
        props.setProperty("directory", this.directory);
        props.setProperty("clearHistoryOnStartup", String.valueOf(this.clearHistoryOnStartup));
        props.setProperty("quality", this.quality);
        props.setProperty("compatibilityMode", String.valueOf(this.compatibilityMode));
        try (FileOutputStream output = new FileOutputStream(CONFIG_FILE)) {
            props.store(output, "YT-DLP Configuration");
            log.info("Configuration saved successfully");
        } catch (IOException e) {
            throw new RuntimeException("Failed to save configuration", e);
        }
    }

    public void updateAllSettings(String directory,
                                  boolean clearHistoryOnStartup,
                                  String quality,
                                  boolean compatibilityMode) {
        this.directory = directory;
        this.clearHistoryOnStartup = clearHistoryOnStartup;
        this.quality = quality;
        this.compatibilityMode = compatibilityMode;
        saveConfig();
    }
}