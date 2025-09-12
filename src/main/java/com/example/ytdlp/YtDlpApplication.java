package com.example.ytdlp;

import com.example.ytdlp.utils.tray.TrayManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class YtDlpApplication {
    public static void main(String[] args) {
        // Отключаем headless режим для поддержки системного трея
        System.setProperty("java.awt.headless", "false");

        // Поднимаем контест для последующего извлечения бина с треем
        ConfigurableApplicationContext context = SpringApplication.run(YtDlpApplication.class, args);

        // Инициализируем системный трей
        TrayManager trayManager = context.getBean(TrayManager.class);

        // Открываем веб-страницу
        trayManager.openWebInterface();

        // Добавляем обработчик завершения приложения
        Runtime.getRuntime().addShutdownHook(new Thread(trayManager::removeTrayIcon));
    }
}