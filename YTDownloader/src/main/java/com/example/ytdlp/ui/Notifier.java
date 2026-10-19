package com.example.ytdlp.ui;

public interface Notifier {
    void showNotification(String message, boolean success);
    void refreshDownloads();
}