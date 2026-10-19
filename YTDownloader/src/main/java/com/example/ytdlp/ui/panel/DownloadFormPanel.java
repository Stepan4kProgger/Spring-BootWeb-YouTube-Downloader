package com.example.ytdlp.ui.panel;

import com.example.ytdlp.config.ApplicationConfig;
import com.example.ytdlp.ui.Notifier;
import com.example.ytdlp.ui.SwingUtils;
import com.example.ytdlp.ui.Theme;
import com.example.ytdlp.ui.controller.UiController;
import com.example.ytdlp.utils.model.DownloadRequest;
import com.example.ytdlp.utils.model.DownloadResponse;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.util.Objects;

public class DownloadFormPanel extends JPanel {
    private final UiController controller;
    private final Notifier notifier;

    private JTextField urlField;
    private JTextField directoryField;
    private JCheckBox clearHistoryCheck;
    private JComboBox<String> qualityCombo;
    private JCheckBox compatibilityCheck;
    private JButton saveButton;
    private JButton downloadButton;

    private String  originalDirectory;
    private boolean originalClear;
    private String  originalQuality;
    private boolean originalCompat;

    private static final String[] QUALITIES = {
            "worst", "144", "240", "360", "480", "720", "1080", "1440", "2160", "4320", "best"
    };

    public DownloadFormPanel(UiController controller, Notifier notifier) {
        this.controller = controller;
        this.notifier = notifier;
        setLayout(new BorderLayout());
        setBackground(Theme.bg());

        JPanel card = SwingUtils.roundedCard(Theme.card(), Theme.border(), 12, 20);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JLabel urlLabel = SwingUtils.sectionLabel("Ссылка на видео *");

        JPanel urlRow = new JPanel(new BorderLayout(6, 0));
        urlRow.setBackground(Theme.card());
        urlRow.setOpaque(false);
        urlRow.setAlignmentX(LEFT_ALIGNMENT);
        urlRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        urlField = SwingUtils.textField(14);
        JButton pasteBtn = new JButton("Вставить");
        SwingUtils.styleSecondaryButton(pasteBtn);
        pasteBtn.setToolTipText("Вставить из буфера обмена");
        pasteBtn.addActionListener(e -> pasteFromClipboard());

        urlRow.add(urlField, BorderLayout.CENTER);
        urlRow.add(pasteBtn, BorderLayout.EAST);

        card.add(urlLabel);
        card.add(Box.createVerticalStrut(6));
        card.add(urlRow);
        card.add(Box.createVerticalStrut(4));
        card.add(SwingUtils.hintLabel("Поддерживаются: YouTube, Vimeo, Twitter и другие"));
        card.add(Box.createVerticalStrut(18));

        JPanel settings = new JPanel();
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));
        settings.setBackground(Theme.card());
        settings.setOpaque(false);
        settings.setAlignmentX(LEFT_ALIGNMENT);
        settings.setBorder(BorderFactory.createCompoundBorder(
                SwingUtils.roundedBorder(Theme.border(), 10),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)
        ));
        settings.setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));

        JLabel settingsTitle = SwingUtils.sectionLabel("Настройки");
        settings.add(settingsTitle);
        settings.add(Box.createVerticalStrut(12));

        settings.add(SwingUtils.fieldLabel("Путь для загрузки *"));

        JPanel dirRow = new JPanel(new BorderLayout(6, 0));
        dirRow.setBackground(Theme.card());
        dirRow.setOpaque(false);
        dirRow.setAlignmentX(LEFT_ALIGNMENT);
        dirRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));

        directoryField = SwingUtils.textField(13);
        JButton browseBtn = new JButton("Обзор");
        SwingUtils.styleSecondaryButton(browseBtn);
        browseBtn.setToolTipText("Выбрать папку");
        browseBtn.addActionListener(e -> browseDirectory());

        dirRow.add(directoryField, BorderLayout.CENTER);
        dirRow.add(browseBtn, BorderLayout.EAST);
        settings.add(Box.createVerticalStrut(4));
        settings.add(dirRow);
        settings.add(Box.createVerticalStrut(12));

        clearHistoryCheck = checkbox("Очищать историю при запуске");
        settings.add(clearHistoryCheck);
        settings.add(SwingUtils.hintLabel("История будет очищаться при каждом запуске"));
        settings.add(Box.createVerticalStrut(12));

        settings.add(SwingUtils.fieldLabel("Качество видео"));
        settings.add(Box.createVerticalStrut(4));
        qualityCombo = new JComboBox<>(QUALITIES);
        qualityCombo.setBackground(Theme.fieldBg());
        qualityCombo.setForeground(Theme.text());
        qualityCombo.setFont(SwingUtils.font(Font.PLAIN, 13));
        qualityCombo.setAlignmentX(LEFT_ALIGNMENT);
        qualityCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        settings.add(qualityCombo);
        settings.add(Box.createVerticalStrut(12));

        compatibilityCheck = checkbox("Совместимость со старыми устройствами");
        settings.add(compatibilityCheck);
        settings.add(SwingUtils.hintLabel("H.264, AAC, MP4 — для плееров и приставок"));
        settings.add(Box.createVerticalStrut(14));

        saveButton = new JButton("Сохранить настройки");
        SwingUtils.styleSecondaryButton(saveButton);
        saveButton.setAlignmentX(LEFT_ALIGNMENT);
        saveButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        saveButton.addActionListener(e -> saveSettings());
        settings.add(saveButton);

        card.add(settings);
        card.add(Box.createVerticalStrut(18));

        downloadButton = new JButton("Скачать");
        SwingUtils.stylePrimaryButton(downloadButton);
        downloadButton.setFont(SwingUtils.font(Font.BOLD, 14));
        downloadButton.setAlignmentX(LEFT_ALIGNMENT);
        downloadButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        downloadButton.addActionListener(e -> startDownload());
        card.add(downloadButton);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Theme.bg());
        wrapper.add(card, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(wrapper);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(Theme.bg());
        add(scroll, BorderLayout.CENTER);

        urlField.getDocument().addDocumentListener(simpleDocListener(this::updateButtons));
        directoryField.getDocument().addDocumentListener(simpleDocListener(this::onSettingsChanged));
        clearHistoryCheck.addActionListener(e -> onSettingsChanged());
        qualityCombo.addActionListener(e -> onSettingsChanged());
        compatibilityCheck.addActionListener(e -> onSettingsChanged());

        loadSettings();
    }

    private JCheckBox checkbox(String text) {
        JCheckBox cb = new JCheckBox(text);
        cb.setBackground(Theme.card());
        cb.setOpaque(false);
        cb.setForeground(Theme.text());
        cb.setFont(SwingUtils.font(Font.PLAIN, 13));
        cb.setAlignmentX(LEFT_ALIGNMENT);
        cb.setFocusPainted(false);
        return cb;
    }

    private DocumentListener simpleDocListener(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    private void loadSettings() {
        ApplicationConfig cfg = controller.getAppConfig();
        directoryField.setText(cfg.getDirectory());
        clearHistoryCheck.setSelected(cfg.isClearHistoryOnStartup());
        qualityCombo.setSelectedItem(cfg.getQuality());
        compatibilityCheck.setSelected(cfg.isCompatibilityMode());
        snapshotSettings();
    }

    private void snapshotSettings() {
        originalDirectory = directoryField.getText();
        originalClear = clearHistoryCheck.isSelected();
        originalQuality = (String) qualityCombo.getSelectedItem();
        originalCompat = compatibilityCheck.isSelected();
        updateButtons();
    }

    private boolean hasUnsavedChanges() {
        return !Objects.equals(originalDirectory, directoryField.getText())
                || originalClear != clearHistoryCheck.isSelected()
                || !Objects.equals(originalQuality, qualityCombo.getSelectedItem())
                || originalCompat != compatibilityCheck.isSelected();
    }

    private void onSettingsChanged() { updateButtons(); }

    private void updateButtons() {
        boolean changed = hasUnsavedChanges();

        saveButton.setEnabled(changed);

        boolean canDownload = !urlField.getText().trim().isEmpty()
                && !directoryField.getText().trim().isEmpty()
                && !changed;

        downloadButton.setEnabled(canDownload);
    }

    private void saveSettings() {
        try {
            controller.saveSettings(
                    directoryField.getText().trim(),
                    clearHistoryCheck.isSelected(),
                    (String) qualityCombo.getSelectedItem(),
                    compatibilityCheck.isSelected());
            snapshotSettings();
            notifier.showNotification("Настройки сохранены", true);
        } catch (Exception ex) {
            notifier.showNotification("Ошибка сохранения: " + ex.getMessage(), false);
        }
    }

    private void startDownload() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            notifier.showNotification("Введите ссылку", false);
            return;
        }

        downloadButton.setEnabled(false);
        downloadButton.setText("Загрузка...");

        DownloadRequest req = new DownloadRequest();
        req.setUrl(url);

        new SwingWorker<DownloadResponse, Void>() {
            @Override protected DownloadResponse doInBackground() {
                return controller.startDownload(req);
            }
            @Override protected void done() {
                try {
                    DownloadResponse resp = get();
                    if (resp.isSuccess()) {
                        notifier.showNotification("Готово: " + resp.getFilename(), true);
                        urlField.setText("");
                    } else {
                        notifier.showNotification("Ошибка: " + resp.getError(), false);
                    }
                } catch (Exception ex) {
                    notifier.showNotification("Ошибка: " + ex.getMessage(), false);
                } finally {
                    downloadButton.setText("Скачать");
                    updateButtons();
                    notifier.refreshDownloads();
                }
            }
        }.execute();
    }

    private void browseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setDialogTitle("Выберите папку для загрузки");
        String cur = directoryField.getText().trim();
        if (!cur.isEmpty()) {
            File f = new File(cur);
            if (f.exists()) chooser.setCurrentDirectory(f);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            directoryField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void pasteFromClipboard() {
        try {
            String text = (String) Toolkit.getDefaultToolkit()
                    .getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (text != null && !text.trim().isEmpty()) {
                urlField.setText(text.trim());
            }
        } catch (Exception ex) {
            notifier.showNotification("Не удалось прочитать буфер обмена", false);
        }
    }

    public String getUrl() { return urlField.getText(); }
    public void setUrl(String url) { urlField.setText(url); }
}