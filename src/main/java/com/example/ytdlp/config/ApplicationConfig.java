package com.example.ytdlp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.File;
import java.util.concurrent.Executor;

@Data
@Configuration
@EnableAsync
@ConfigurationProperties(prefix = "app.download")
public class ApplicationConfig {

    private String directory = "D:/downloads";
    private boolean rememberLastDirectory = true;

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
        // Нормализуем путь
        this.directory = directory.replace("/", File.separator)
                .replace("\\", File.separator);
    }
}