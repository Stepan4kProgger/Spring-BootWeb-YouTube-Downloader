package com.example.launcher.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LaunchManagerService {

    private final ProcessLauncherService processLauncherService;
    private final ConfigurableApplicationContext context;

    public LaunchManagerService(ProcessLauncherService processLauncherService, ConfigurableApplicationContext context) {
        this.processLauncherService = processLauncherService;
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void launchApplications() {
        try {
            log.info("Запуск лаунчера");

            if (!processLauncherService.checkFilesExist()) {
                log.error("Основное приложение не найдено | Завершение работы");
                Thread.sleep(5000);
                return;
            }

            log.info("=== ЗАПУСК ОБНОВЛЕНИЯ ===");
            processLauncherService.runUpdater();

            log.info("=== ЗАПУСК ОСНОВНОГО ПРИЛОЖЕНИЯ ===");
            boolean appStarted = processLauncherService.runApplication();

            if (appStarted) {
                log.info("Основное приложение запущено | Завершаем работу лаунчера");
                System.exit(SpringApplication.exit(context, () -> 0));
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