package com.example.ytdlp.utils.tray;

import com.example.ytdlp.service.YtDlpService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrayManager {
    private final YtDlpService ytDlpService;
    private final ApplicationContext context;

    private TrayIcon trayIcon;
    private SystemTray systemTray;

    @PostConstruct
    public void init() {
        if (isHeadlessOrUnsupported()) {
            log.warn("System tray is not supported in this environment");
            return;
        }

        try {
            initializeTrayIcon();
            log.info("System tray icon initialized successfully");
        } catch (Exception e) {
            log.error("Error initializing system tray", e);
        }
    }

    private boolean isHeadlessOrUnsupported() {
        return GraphicsEnvironment.isHeadless() || !SystemTray.isSupported();
    }

    private void initializeTrayIcon() throws AWTException {
        systemTray = SystemTray.getSystemTray();

        trayIcon = new TrayIcon(loadTrayImage(), "YT-DLP Downloader");
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> openWebInterface());
        trayIcon.setPopupMenu(createPopupMenu());

        systemTray.add(trayIcon);
    }

    private Image loadTrayImage() {
        Image image = Toolkit.getDefaultToolkit().getImage(getClass().getResource("/icon.png"));
        return image != null ? image : createDefaultIcon();
    }

    private Image createDefaultIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 16, 16);
        g.setColor(Color.WHITE);
        g.drawString("Y", 4, 12);
        g.dispose();
        return image;
    }

    private PopupMenu createPopupMenu() {
        PopupMenu popup = new PopupMenu();

        MenuItem openItem = new MenuItem("Открыть");
        openItem.addActionListener(e -> openWebInterface());
        popup.add(openItem);

        popup.addSeparator();

        MenuItem exitItem = new MenuItem("Закрыть");
        exitItem.addActionListener(e -> shutdown());
        popup.add(exitItem);

        return popup;
    }

    public static void openWebInterface() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:8080/yt-dlp/"));
            } else {
                openFallbackBrowser();
            }
        } catch (Exception ex) {
            log.error("Error opening web interface", ex);
        }
    }

    private static void openFallbackBrowser() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String command;

            if (os.contains("win")) {
                command = "rundll32 url.dll,FileProtocolHandler http://localhost:8080/yt-dlp/";
            } else if (os.contains("mac")) {
                command = "open http://localhost:8080/yt-dlp/";
            } else if (os.contains("nix") || os.contains("nux")) {
                command = "xdg-open http://localhost:8080/yt-dlp/";
            } else {
                log.warn("Unsupported OS for browser fallback");
                return;
            }
            //Runtime.getRuntime().exec(command);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.start();
        } catch (Exception e) {
            log.error("Error opening browser fallback", e);
        }
    }

    private void shutdown() {
        if (!confirmShutdown()) return;

        try {
            performGracefulShutdown();
        } catch (Exception e) {
            handleShutdownError(e);
        }
    }

    private boolean confirmShutdown() {
        Object[] options = {"Да", "Нет"};

        int result = JOptionPane.showOptionDialog(
                null,
                "Вы уверены, что хотите закрыть приложение?\nВсе активные загрузки будут остановлены.",
                "Подтверждение закрытия",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[1]
        );

        return result == JOptionPane.YES_OPTION; // 0 = Да, 1 = Нет
    }

    private void performGracefulShutdown() throws Exception {
        log.info("Initiating graceful shutdown...");
        ytDlpService.stopAllDownloads();
        Thread.sleep(1000);
        removeTrayIcon();

        int exitCode = SpringApplication.exit(context, () -> 0);
        log.info("Shutdown completed with code: {}", exitCode);
        System.exit(exitCode);
    }

    private void handleShutdownError(Exception e) {
        log.error("Error during shutdown", e);

        if (trayIcon != null) {
            trayIcon.displayMessage(
                    "YT-DLP Downloader",
                    "Ошибка при завершении: " + e.getMessage(),
                    TrayIcon.MessageType.ERROR
            );
        }

        System.exit(1);
    }

    public void removeTrayIcon() {
        if (trayIcon != null && systemTray != null) {
            try {
                systemTray.remove(trayIcon);
                log.info("Tray icon removed successfully");
            } catch (Exception e) {
                log.error("Error removing tray icon", e);
            }
        }
    }
}