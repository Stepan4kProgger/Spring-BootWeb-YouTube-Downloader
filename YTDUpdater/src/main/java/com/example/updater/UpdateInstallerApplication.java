package com.example.updater;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.swing.*;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

@SpringBootApplication
public class UpdateInstallerApplication {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Ignore - use default L&F
        }
        SpringApplication.run(UpdateInstallerApplication.class, args);
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static Path resolvePath(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        String home = System.getProperty("updater.home");
        Path base = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.dir"));
        return base.resolve(p).normalize();
    }
}