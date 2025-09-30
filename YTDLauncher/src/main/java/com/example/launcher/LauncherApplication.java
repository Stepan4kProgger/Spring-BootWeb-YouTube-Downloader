package com.example.launcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LauncherApplication {
    public static void main(String[] args) throws InterruptedException {
        try {
            SpringApplication.run(LauncherApplication.class, args);
        } catch (Exception e) {
            System.err.println("Ошибка при запуске лаунчера: " + e.getMessage());
        }
    }
}