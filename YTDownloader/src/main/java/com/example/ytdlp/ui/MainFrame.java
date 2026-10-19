package com.example.ytdlp.ui;

import com.example.ytdlp.service.ShutdownService;
import com.example.ytdlp.ui.controller.UiController;
import com.example.ytdlp.ui.panel.DownloadFormPanel;
import com.example.ytdlp.ui.panel.DownloadsPanel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class MainFrame extends JFrame implements Notifier {
    private final UiController controller;
    private final ShutdownService shutdownService;

    private DownloadFormPanel formPanel;
    private DownloadsPanel downloadsPanel;
    private JLabel statusLabel;
    private Timer refreshTimer;
    private final AtomicBoolean closing = new AtomicBoolean(false);

    public MainFrame(UiController controller, ShutdownService shutdownService) {
        this.controller = controller;
        this.shutdownService = shutdownService;
        setTitle("YT-DLP Downloader");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { confirmExit(); }
        });

        try {
            SwingUtilities.invokeAndWait(this::buildUI);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        setSize(1280, 820);
        setMinimumSize(new Dimension(960, 640));
        setLocationRelativeTo(null);

        refreshTimer = new Timer(2500, e -> {
            if (downloadsPanel != null && !closing.get()) downloadsPanel.refresh();
        });
        refreshTimer.start();
    }

    private void buildUI() { setContentPane(createRootPanel()); }

    private JPanel createRootPanel() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.bg());
        root.add(createHeader(), BorderLayout.NORTH);

        formPanel = new DownloadFormPanel(controller, this);
        downloadsPanel = new DownloadsPanel(controller, this);

        JPanel split = new JPanel(new GridBagLayout());
        split.setBackground(Theme.bg());
        split.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;
        gbc.insets = new Insets(0, 6, 0, 6);

        gbc.gridx = 0; gbc.weightx = 0.55;
        split.add(formPanel, gbc);

        gbc.gridx = 1; gbc.weightx = 0.45;
        split.add(downloadsPanel, gbc);

        root.add(split, BorderLayout.CENTER);

        JPanel status = new JPanel(new BorderLayout());
        status.setBackground(Theme.bg());
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.border()),
                BorderFactory.createEmptyBorder(8, 20, 8, 20)
        ));
        statusLabel = new JLabel("Готово");
        statusLabel.setFont(SwingUtils.font(Font.PLAIN, 12));
        statusLabel.setForeground(Theme.textMuted());
        status.add(statusLabel, BorderLayout.WEST);
        root.add(status, BorderLayout.SOUTH);

        return root;
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.bg());
        header.setBorder(BorderFactory.createEmptyBorder(18, 24, 14, 20));

        JPanel brand = new JPanel();
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBackground(Theme.bg());

        JLabel title = new JLabel("YT-DLP Downloader");
        title.setFont(SwingUtils.font(Font.BOLD, 20));
        title.setForeground(Theme.text());
        title.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Скачивайте видео и аудио с различных платформ");
        subtitle.setFont(SwingUtils.font(Font.PLAIN, 12));
        subtitle.setForeground(Theme.textMuted());
        subtitle.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        brand.add(title);
        brand.add(Box.createVerticalStrut(3));
        brand.add(subtitle);
        header.add(brand, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setBackground(Theme.bg());

        JLabel versionsLabel = new JLabel("загрузка…");
        versionsLabel.setFont(SwingUtils.font(Font.PLAIN, 11));
        versionsLabel.setForeground(Theme.textMuted());
        right.add(versionsLabel);

        right.add(Box.createHorizontalStrut(8));

        JButton lightBtn = new JButton("Светлая");
        SwingUtils.styleSecondaryButton(lightBtn);
        lightBtn.setToolTipText("Светлая тема");
        lightBtn.addActionListener(e -> applyTheme(false));

        JButton darkBtn = new JButton("Тёмная");
        SwingUtils.styleSecondaryButton(darkBtn);
        darkBtn.setToolTipText("Тёмная тема");
        darkBtn.addActionListener(e -> applyTheme(true));

        right.add(lightBtn);
        right.add(darkBtn);
        header.add(right, BorderLayout.EAST);

        new SwingWorker<String[], Void>() {
            @Override protected String[] doInBackground() {
                return new String[]{controller.getYtDlpVersion(), controller.getFfmpegVersion()};
            }
            @Override protected void done() {
                try {
                    String[] v = get();
                    versionsLabel.setText("yt-dlp: " + v[0] + "  ·  ffmpeg: " + v[1]);
                } catch (Exception ex) {
                    versionsLabel.setText("Версии недоступны");
                }
            }
        }.execute();

        return header;
    }

    public void applyTheme(boolean dark) {
        Theme.dark = dark;
        String savedUrl = formPanel != null ? formPanel.getUrl() : "";
        refreshTimer.stop();
        buildUI();
        if (formPanel != null) formPanel.setUrl(savedUrl);
        refreshTimer.start();
        revalidate();
        repaint();
    }

    private void confirmExit() {
        if (!closing.compareAndSet(false, true)) return;

        int r = JOptionPane.showConfirmDialog(this,
                "Вы уверены, что хотите закрыть приложение?\nВсе активные загрузки будут остановлены.",
                "Подтверждение закрытия",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (r != JOptionPane.YES_OPTION) {
            closing.set(false);
            return;
        }

        refreshTimer.stop();
        setEnabled(false);
        statusLabel.setText("Завершение работы...");

        Thread stopper = new Thread(() -> {
            try {
                shutdownService.shutdown();
            } catch (Exception e) {
                log.error("Shutdown error", e);
            } finally {
                log.info("Calling System.exit(0)");
                System.exit(0);
            }
        }, "main-frame-shutdown");
        stopper.setDaemon(true);
        stopper.start();
    }

    @Override
    public void showNotification(String message, boolean success) {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel == null) return;
            statusLabel.setText(message);
            statusLabel.setForeground(success ? Theme.success() : Theme.danger());
            Timer clear = new Timer(5000, e -> {
                statusLabel.setText("Готово");
                statusLabel.setForeground(Theme.textMuted());
            });
            clear.setRepeats(false);
            clear.start();
        });
    }

    @Override
    public void refreshDownloads() {
        if (downloadsPanel != null && !closing.get()) downloadsPanel.refresh();
    }
}