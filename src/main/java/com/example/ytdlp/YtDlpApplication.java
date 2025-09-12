package com.example.ytdlp;

import com.example.ytdlp.utils.tray.TrayManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.Socket;

@SpringBootApplication
public class YtDlpApplication {
    public static void main(String[] args) {
        // Отключаем headless режим для поддержки системного трея
        System.setProperty("java.awt.headless", "false");

        // Если порт занят, просто открываем веб-страницу и прекращаем работу
        try (Socket ignored = new Socket("localhost", 8080)) {
            TrayManager.openWebInterface();
            return;
        } catch (IOException ignored) {}

        // Поднимаем контест запускаемой программы для последующего извлечения бина с треем
        ConfigurableApplicationContext context = SpringApplication.run(YtDlpApplication.class, args);

        // Инициализируем системный трей
        TrayManager trayManager = context.getBean(TrayManager.class);

        // Открываем веб-страницу
        TrayManager.openWebInterface();

        // Добавляем обработчик завершения приложения
        Runtime.getRuntime().addShutdownHook(new Thread(trayManager::removeTrayIcon));
    }
}