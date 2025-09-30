package com.example.updater.utils;

import javax.swing.*;
import java.awt.*;

public class ProgressDialog {
    private JDialog dialog;
    private JProgressBar progressBar;
    private JLabel messageLabel;

    public ProgressDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        // Проверяем, находимся ли мы в EDT
        if (SwingUtilities.isEventDispatchThread()) {
            initializeDialog();
        } else {
            try {
                // Если не в EDT, используем invokeAndWait
                SwingUtilities.invokeAndWait(this::initializeDialog);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void initializeDialog() {
        JFrame parentFrame = new JFrame();
        parentFrame.setAlwaysOnTop(true);

        dialog = new JDialog(parentFrame, "Обновление", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setSize(350, 120);
        dialog.setLocationRelativeTo(null);
        dialog.setResizable(false);

        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        messageLabel = new JLabel("Проверка обновлений...", SwingConstants.CENTER);
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);

        panel.add(messageLabel, BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);

        dialog.add(panel);
    }

    public void setVisible(boolean visible) {
        if (dialog == null) return;

        if (SwingUtilities.isEventDispatchThread()) {
            dialog.setVisible(visible);
            if (visible) {
                dialog.toFront();
                dialog.requestFocus();
            }
        } else {
            SwingUtilities.invokeLater(() -> {
                dialog.setVisible(visible);
                if (visible) {
                    dialog.toFront();
                    dialog.requestFocus();
                }
            });
        }
    }

    public void setMessage(String message) {
        if (messageLabel == null) return;

        if (SwingUtilities.isEventDispatchThread()) {
            messageLabel.setText(message);
            dialog.pack();
        } else {
            SwingUtilities.invokeLater(() -> {
                messageLabel.setText(message);
                dialog.pack();
            });
        }
    }

    public void setProgress(int progress) {
        if (progressBar == null) return;

        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(progress);
            progressBar.setString(progress + "%");
            progressBar.repaint(); // Принудительная перерисовка
        });
    }

    public void close() {
        if (dialog == null) return;

        if (SwingUtilities.isEventDispatchThread()) {
            dialog.dispose();
            Window[] windows = Window.getWindows();
            for (Window window : windows) {
                if (window.isDisplayable()) {
                    window.dispose();
                }
            }
        } else {
            SwingUtilities.invokeLater(() -> {
                dialog.dispose();
                Window[] windows = Window.getWindows();
                for (Window window : windows) {
                    if (window.isDisplayable()) {
                        window.dispose();
                    }
                }
            });
        }
    }

    public void setIndeterminate(boolean indeterminate) {
        if (progressBar == null) return;

        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(indeterminate);
            progressBar.setStringPainted(!indeterminate);
        });
    }
}