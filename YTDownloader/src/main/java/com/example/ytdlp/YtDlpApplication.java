package com.example.ytdlp;

import com.example.ytdlp.service.ShutdownService;
import com.example.ytdlp.ui.MainFrame;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import javax.swing.*;

@SpringBootApplication
public class YtDlpApplication {
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");

        ConfigurableApplicationContext context =
                SpringApplication.run(YtDlpApplication.class, args);

        // Захватываем ссылку до того, как контекст может быть закрыт.
        ShutdownService shutdownService = context.getBean(ShutdownService.class);

        // JVM shutdown hook: срабатывает на System.exit, Ctrl+C, logout, внешний kill.
        // ShutdownService идемпотентен — повторный вызов безопасен.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdownService.shutdown();
            } catch (Exception ignored) {}
        }, "app-shutdown-hook"));

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = context.getBean(MainFrame.class);
            frame.setVisible(true);
        });
    }
}