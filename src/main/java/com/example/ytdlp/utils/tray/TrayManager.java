package com.example.ytdlp.utils.tray;

import com.example.ytdlp.service.YtDlpService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.net.URI;

@Slf4j
@Component
public class TrayManager {

    @Autowired
    private YtDlpService ytDlpService;

    @Autowired
    private ApplicationContext context;

    private TrayIcon trayIcon;

    @PostConstruct
    public void init() {
        // Дополнительная проверка на headless режим
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("Graphics environment is headless, system tray not supported");
            return;
        }

        if (!SystemTray.isSupported()) {
            log.warn("System tray is not supported");
            return;
        }

        try {
            // Получаем системный трей
            SystemTray systemTray = SystemTray.getSystemTray();

            // Загружаем иконку
            Image image = Toolkit.getDefaultToolkit().getImage(getClass().getResource("/icon.png"));
            if (image == null) {
                log.warn("Icon image not found at /icon.png");
                // Создаем пустую иконку как fallback
                image = createDefaultIcon();
            }

            // Создаем иконку для трея
            trayIcon = new TrayIcon(image, "YT-DLP Downloader");

            // Включаем авторазмер иконки
            trayIcon.setImageAutoSize(true);

            // Обработчик клика левой кнопкой мыши по иконке
            trayIcon.addActionListener(e -> openWebInterface());

            // Создаем всплывающее меню
            PopupMenu popup = new PopupMenu();

            // Пункт меню для открытия веб-интерфейса
            MenuItem openItem = new MenuItem("Открыть");
            openItem.addActionListener(e -> openWebInterface());
            popup.add(openItem);

            // Разделитель
            popup.addSeparator();

            // Пункт меню для выхода
            MenuItem exitItem = new MenuItem("Закрыть");
            exitItem.addActionListener(e -> shutdown());
            popup.add(exitItem);

            // Устанавливаем меню для иконки
            trayIcon.setPopupMenu(popup);

            // Добавляем иконку в системный трей
            systemTray.add(trayIcon);

            log.info("System tray icon initialized successfully");

        } catch (AWTException e) {
            log.error("Error adding tray icon to system tray", e);
        } catch (Exception e) {
            log.error("Error initializing system tray", e);
        }
    }

    private Image createDefaultIcon() {
        // Создаем простую иконку программно как fallback
        int size = 16;
        Image image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = (Graphics2D) image.getGraphics();
        g2d.setColor(Color.BLUE);
        g2d.fillRect(0, 0, size, size);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Y", 4, 12);
        g2d.dispose();
        return image;
    }

    private void openWebInterface() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:8080/yt-dlp/"));
            } else {
                log.warn("Desktop browsing is not supported on this platform");
                // Альтернативный способ: открыть в default browser
                openFallbackBrowser();
            }
        } catch (Exception ex) {
            log.error("Error opening web interface", ex);
        }
    }

    private void openFallbackBrowser() {
        try {
            Runtime runtime = Runtime.getRuntime();
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                runtime.exec("rundll32 url.dll,FileProtocolHandler http://localhost:8080/yt-dlp/");
            } else if (os.contains("mac")) {
                runtime.exec("open http://localhost:8080/yt-dlp/");
            } else if (os.contains("nix") || os.contains("nux")) {
                runtime.exec("xdg-open http://localhost:8080/yt-dlp/");
            }
        } catch (Exception e) {
            log.error("Error opening browser fallback", e);
        }
    }

    private void shutdown() {
        try {
            // Создаем диалоговое окно подтверждения
            int result = JOptionPane.showConfirmDialog(
                    null,
                    "Вы уверены, что хотите закрыть приложение?\nВсе активные загрузки будут остановлены.",
                    "Подтверждение закрытия",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );

            if (result == JOptionPane.YES_OPTION) {
                // Показываем уведомление о начале завершения
                if (trayIcon != null) {
                    trayIcon.displayMessage(
                            "YT-DLP Downloader",
                            "Завершение работы... Останавливаем загрузки",
                            TrayIcon.MessageType.INFO
                    );
                }

                // 1. Останавливаем все активные загрузки
                log.info("Initiating graceful shutdown...");
                ytDlpService.stopAllDownloads();

                // 2. Даем время для завершения операций
                Thread.sleep(1000);

                // 3. Удаляем иконку из трея
                removeTrayIcon();

                // 4. Завершаем Spring приложение
                int exitCode = SpringApplication.exit(context, () -> {
                    log.info("Spring application exited gracefully");
                    return 0;
                });

                // 5. Принудительно завершаем JVM
                log.info("Shutdown completed with code: {}", exitCode);
                System.exit(exitCode);
            }
        } catch (Exception e) {
            log.error("Error during shutdown", e);

            // Все равно пытаемся завершиться
            try {
                if (trayIcon != null) {
                    trayIcon.displayMessage(
                            "YT-DLP Downloader",
                            "Ошибка при завершении: " + e.getMessage(),
                            TrayIcon.MessageType.ERROR
                    );
                }
            } catch (Exception ex) {
                log.error("Error showing shutdown error message", ex);
            }

            System.exit(1);
        }
    }

    public void removeTrayIcon() {
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
                log.info("Tray icon removed successfully");
            } catch (Exception e) {
                log.error("Error removing tray icon", e);
            }
        }
    }
}