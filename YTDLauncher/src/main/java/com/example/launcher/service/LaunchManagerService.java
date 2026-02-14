package com.example.launcher.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LaunchManagerService {

    private final ProcessLauncherService processLauncherService;

    @Value("${app.startup.delay:1000}")
    private long startupDelay;

    public LaunchManagerService(ProcessLauncherService processLauncherService) {
        this.processLauncherService = processLauncherService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void launchApplications() {
        try {
            log.info("Запуск лаунчера... Ожидание {}ms", startupDelay);
            Thread.sleep(startupDelay);

            // Проверяем только основное приложение
            if (!processLauncherService.checkFilesExist()) {
                log.error("Основное приложение не найдено. Завершение работы.");
                Thread.sleep(5000);
                return; // Просто возвращаемся, не завершаем приложение
            }

            log.info("=== ЗАПУСК ОБНОВЛЕНИЯ ===");
            processLauncherService.runUpdater(); // updater опционален

            Thread.sleep(1000);

            log.info("=== ЗАПУСК ОСНОВНОГО ПРИЛОЖЕНИЯ ===");
            boolean appStarted = processLauncherService.runApplication();

            if (appStarted) {
                log.info("Лаунчер завершает работу. Основное приложение работает.");
                // Spring Boot приложение продолжит работать в фоне
            } else {
                log.error("Не удалось запустить основное приложение");
            }

        } catch (InterruptedException e) {
            log.error("Прерывание во время запуска: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Критическая ошибка при запуске: {}", e.getMessage(), e);
        }
    }
}