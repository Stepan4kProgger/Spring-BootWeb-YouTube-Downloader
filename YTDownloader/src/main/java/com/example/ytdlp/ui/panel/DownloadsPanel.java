package com.example.ytdlp.ui.panel;

import com.example.ytdlp.ui.Notifier;
import com.example.ytdlp.ui.SwingUtils;
import com.example.ytdlp.ui.Theme;
import com.example.ytdlp.ui.controller.UiController;
import com.example.ytdlp.utils.model.DownloadProgress;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DownloadsPanel extends JPanel {
    private final UiController controller;
    private final Notifier notifier;
    private final JPanel itemsPanel;
    private final JScrollPane scrollPane;

    private Timer indeterminateTimer;
    private final List<JProgressBar> indeterminateBars = new ArrayList<>();
    private final Map<JProgressBar, Integer> barDirections = new HashMap<>();
    private final Map<JProgressBar, String> barIds = new HashMap<>();
    private final Map<String, Integer> animValues = new HashMap<>();
    private final Map<String, Integer> animDirs = new HashMap<>();

    public DownloadsPanel(UiController controller, Notifier notifier) {
        this.controller = controller;
        this.notifier = notifier;
        setLayout(new BorderLayout());
        setBackground(Theme.bg());

        JPanel card = SwingUtils.roundedCard(Theme.card(), Theme.border(), 12, 0);
        card.setLayout(new BorderLayout());

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.border()),
                BorderFactory.createEmptyBorder(14, 18, 14, 12)
        ));

        JLabel title = new JLabel("Загрузки");
        title.setFont(SwingUtils.font(Font.BOLD, 15));
        title.setForeground(Theme.text());
        header.add(title, BorderLayout.WEST);

        JButton clearBtn = new JButton("Очистить");
        SwingUtils.styleSecondaryButton(clearBtn);
        clearBtn.setToolTipText("Очистить историю");
        clearBtn.addActionListener(e -> clearHistory());
        header.add(clearBtn, BorderLayout.EAST);

        card.add(header, BorderLayout.NORTH);

        itemsPanel = new JPanel();
        itemsPanel.setLayout(new BoxLayout(itemsPanel, BoxLayout.Y_AXIS));
        itemsPanel.setOpaque(false);
        itemsPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        scrollPane = new JScrollPane(itemsPanel);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getViewport().setBackground(Theme.card());
        card.add(scrollPane, BorderLayout.CENTER);

        add(card, BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        new SwingWorker<List<DownloadProgress>, Void>() {
            @Override protected List<DownloadProgress> doInBackground() {
                return controller.getAllDownloads();
            }
            @Override protected void done() {
                try { rebuild(get()); } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void rebuild(List<DownloadProgress> downloads) {
        if (indeterminateTimer != null) indeterminateTimer.stop();
        indeterminateBars.clear();
        barDirections.clear();
        barIds.clear();

        int scrollPos = scrollPane.getVerticalScrollBar().getValue();
        itemsPanel.removeAll();

        if (downloads.isEmpty()) {
            JPanel empty = new JPanel();
            empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
            empty.setOpaque(false);
            empty.setBorder(BorderFactory.createEmptyBorder(60, 20, 60, 20));

            JLabel title = new JLabel("Пока нет загрузок");
            title.setFont(SwingUtils.font(Font.BOLD, 14));
            title.setForeground(Theme.text());
            title.setAlignmentX(CENTER_ALIGNMENT);

            JLabel text = new JLabel("Скачайте первое видео, используя форму слева");
            text.setFont(SwingUtils.font(Font.PLAIN, 12));
            text.setForeground(Theme.textMuted());
            text.setAlignmentX(CENTER_ALIGNMENT);

            empty.add(title);
            empty.add(Box.createVerticalStrut(6));
            empty.add(text);
            itemsPanel.add(empty);
        } else {
            for (DownloadProgress d : downloads) {
                itemsPanel.add(createItem(d));
                itemsPanel.add(Box.createVerticalStrut(8));
            }
        }

        animValues.keySet().retainAll(barIds.values());
        animDirs.keySet().retainAll(barIds.values());

        if (!indeterminateBars.isEmpty()) {
            if (indeterminateTimer == null) {
                indeterminateTimer = new Timer(30, e -> animateIndeterminate());
            }
            indeterminateTimer.start();
        }

        itemsPanel.revalidate();
        itemsPanel.repaint();
        SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(scrollPos));
    }

    private void animateIndeterminate() {
        for (JProgressBar bar : indeterminateBars) {
            int dir = barDirections.getOrDefault(bar, 1);
            int v = bar.getValue() + 2 * dir;
            if (v >= 100) { v = 100; dir = -1; }
            else if (v <= 0) { v = 0; dir = 1; }
            bar.setValue(v);
            barDirections.put(bar, dir);

            String id = barIds.get(bar);
            if (id != null) {
                animValues.put(id, v);
                animDirs.put(id, dir);
            }
        }
    }

    private JPanel createItem(DownloadProgress d) {
        JPanel item = SwingUtils.roundedCard(Theme.card(), Theme.border(), 10, 14);
        item.setLayout(new BoxLayout(item, BoxLayout.Y_AXIS));
        item.setAlignmentX(LEFT_ALIGNMENT);
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));

        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.setOpaque(false);
        row1.setAlignmentX(LEFT_ALIGNMENT);
        row1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        String filename = d.getFilename() != null ? d.getFilename() : d.getUrl();
        if (filename == null) filename = "Обработка...";

        JLabel nameLabel = new JLabel(truncate(filename, 42));
        nameLabel.setToolTipText(filename);
        nameLabel.setFont(SwingUtils.font(Font.BOLD, 12));
        nameLabel.setForeground(Theme.text());

        String stageLabel = statusText(d.getStatus());
        if (d.getTotalStages() > 0 && isActiveStatus(d.getStatus())) {
            stageLabel += "  " + d.getCurrentStage() + "/" + d.getTotalStages();
        }

        JLabel badge = SwingUtils.pillLabel(" " + stageLabel + " ", statusColor(d.getStatus()));

        row1.add(nameLabel, BorderLayout.CENTER);
        row1.add(badge, BorderLayout.EAST);
        item.add(row1);

        item.add(Box.createVerticalStrut(8));
        String status = d.getStatus();

        if (isActiveStatus(status)) {
            JProgressBar bar = SwingUtils.progressBar();
            boolean indeterminate = isIndeterminate(status) && !"paused".equals(status);
            if (indeterminate) {
                String id = d.getDownloadId();
                int savedVal = animValues.getOrDefault(id, 0);
                int savedDir = animDirs.getOrDefault(id, 1);

                bar.setValue(savedVal);
                bar.setString("...");
                indeterminateBars.add(bar);
                barDirections.put(bar, savedDir);
                barIds.put(bar, id);
            } else {
                bar.setValue(d.getProgress());
                bar.setString(d.getProgress() + "%");
            }
            bar.setAlignmentX(LEFT_ALIGNMENT);
            bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
            bar.setPreferredSize(new Dimension(100, 18));
            item.add(bar);
        } else if ("completed".equals(status)) {
            item.add(statusLine("Завершено", Theme.success()));
        } else if ("error".equals(status)) {
            String msg = d.getErrorMessage() != null ? d.getErrorMessage() : "Ошибка";
            item.add(statusLine("Ошибка: " + truncate(msg, 60), Theme.danger()));
        } else if ("cancelled".equals(status)) {
            item.add(statusLine("Отменено", Theme.textMuted()));
        }

        item.add(Box.createVerticalStrut(10));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);

        String id = d.getDownloadId();
        if ("analyzing".equals(status)
                || "merging".equals(status) || status.startsWith("downloading")) {
            buttons.add(smallBtn("Пауза", "Приостановить", e -> doAction("pause", id)));
            buttons.add(smallBtn("Отмена", "Отменить", e -> doAction("cancel", id)));
        } else if ("paused".equals(status)) {
            buttons.add(smallBtn("Продолж.", "Возобновить", e -> doAction("resume", id)));
            buttons.add(smallBtn("Отмена", "Отменить", e -> doAction("cancel", id)));
        } else if ("completed".equals(status)) {
            buttons.add(smallBtn("Открыть", "Открыть в проводнике", e -> openInExplorer(d)));
            buttons.add(smallBtn("Удалить", "Удалить файл", e -> doAction("delete", id)));
        } else if ("error".equals(status)) {
            buttons.add(smallBtn("Повтор", "Повторить", e -> doAction("retry", id)));
            buttons.add(smallBtn("Удалить", "Удалить из истории", e -> doAction("delete", id)));
        }
        item.add(buttons);

        JPanel info = new JPanel(new BorderLayout());
        info.setOpaque(false);
        info.setAlignmentX(LEFT_ALIGNMENT);
        info.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel dirLabel = new JLabel("Путь: " + (d.getDownloadDirectory() != null ? d.getDownloadDirectory() : ""));
        dirLabel.setFont(SwingUtils.font(Font.PLAIN, 10));
        dirLabel.setForeground(Theme.textMuted());

        String time = "";
        if (d.getEndTime() != null)        time = SwingUtils.formatDateTime(d.getEndTime());
        else if (d.getStartTime() != null) time = SwingUtils.formatDateTime(d.getStartTime());

        JLabel timeLabel = new JLabel(time);
        timeLabel.setFont(SwingUtils.font(Font.PLAIN, 10));
        timeLabel.setForeground(Theme.textMuted());

        info.add(dirLabel, BorderLayout.WEST);
        info.add(timeLabel, BorderLayout.EAST);
        item.add(info);

        return item;
    }

    private JLabel statusLine(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(SwingUtils.font(Font.PLAIN, 12));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private JButton smallBtn(String text, String tooltip, ActionListener l) {
        JButton b = new JButton(text);
        SwingUtils.styleSecondaryButton(b);
        b.setToolTipText(tooltip);
        b.addActionListener(l);
        return b;
    }

    private void doAction(String action, String id) {
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                return switch (action) {
                    case "pause"  -> controller.pause(id);
                    case "resume" -> controller.resume(id);
                    case "cancel" -> controller.cancel(id);
                    case "delete" -> controller.delete(id);
                    case "retry"  -> controller.retry(id);
                    default -> false;
                };
            }
            @Override protected void done() {
                try {
                    boolean ok = get();
                    notifier.showNotification(ok ? "Операция выполнена" : "Не удалось выполнить", ok);
                    refresh();
                } catch (Exception ex) {
                    notifier.showNotification("Ошибка: " + ex.getMessage(), false);
                }
            }
        }.execute();
    }

    private void openInExplorer(DownloadProgress d) {
        try {
            controller.openInExplorer(d.getFilename(), d.getDownloadDirectory());
            notifier.showNotification("Открыто в проводнике", true);
        } catch (Exception ex) {
            notifier.showNotification("Ошибка: " + ex.getMessage(), false);
        }
    }

    private void clearHistory() {
        int r = JOptionPane.showConfirmDialog(this,
                "Очистить всю историю загрузок?",
                "Подтверждение", JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) {
            controller.clearHistory();
            refresh();
            notifier.showNotification("История очищена", true);
        }
    }

    private boolean isActiveStatus(String s) {
        if (s == null) return false;
        return switch (s) {
            case "downloading", "analyzing", "merging", "paused", "finalizing",
                 "downloading_combined", "downloading_video", "downloading_audio" -> true;
            default -> false;
        };
    }

    private boolean isIndeterminate(String status) {
        return "analyzing".equals(status)
                || "merging".equals(status)
                || "finalizing".equals(status);
    }

    private Color statusColor(String status) {
        if (status == null) return Theme.textMuted();
        return switch (status) {
            case "completed" -> Theme.success();
            case "error"     -> Theme.danger();
            case "paused", "finalizing" -> Theme.info();
            case "cancelled" -> Theme.textMuted();
            default          -> Theme.warning();
        };
    }

    private String statusText(String status) {
        if (status == null) return "Неизвестно";
        return switch (status) {
            case "analyzing"              -> "Анализ";
            case "downloading",
                 "downloading_combined"   -> "Скачивание";
            case "downloading_video"      -> "Видео";
            case "downloading_audio"      -> "Аудио";
            case "merging"                -> "Объединение";
            case "finalizing"             -> "Завершение";
            case "completed"              -> "Готово";
            case "error"                  -> "Ошибка";
            case "paused"                 -> "Пауза";
            case "cancelled"              -> "Отменено";
            default                       -> status;
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}