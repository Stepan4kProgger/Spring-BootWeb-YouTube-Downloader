package com.example.ytdlp.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;

@Data
@Component
@ConfigurationProperties(prefix = "app.download")
public class AppConfig {
    private String directory = "./downloads";
    private boolean rememberLastDirectory = true;

    public void setDirectory(String directory) {
        // Нормализуем путь
        this.directory = directory.replace("/", File.separator)
                .replace("\\", File.separator);
    }
}