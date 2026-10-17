package com.example.launcher.service;

import com.example.launcher.model.ProcessInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;

@Slf4j
@Service
public class ProcessLauncherService {

    @Value("${app.updater.path:./updater/updater.jar}")
    private String updaterPath;

    @Value("${app.application.path:./application/demo.jar}")
    private String applicationPath;

    public void runUpdater() {
        if (!Files.exists(Paths.get(updaterPath))) {
            log.warn("Updater не найден: {} | Пропускаем обновление", updaterPath);
            return;
        }
        log.info("Запуск updater: {}", updaterPath);
        ProcessInfo result = runAndWait(updaterPath);
        if (!result.isSuccess()) {
            log.warn("Updater завершился с ошибкой (код: {}), но продолжаем запуск",
                    result.getExitCode());
        }
    }

    public boolean runApplication() {
        if (!Files.exists(Paths.get(applicationPath))) {
            log.error("Основное приложение не найдено: {}", applicationPath);
            return false;
        }
        try {
            new ProcessBuilder("java", "-jar", applicationPath).inheritIO().start();
            log.info("Основное приложение запущено успешно");
            return true;
        } catch (IOException e) {
            log.error("Ошибка при запуске основного приложения: {}", e.getMessage(), e);
            return false;
        }
    }

    private ProcessInfo runAndWait(String jarPath) {
        long startTime = System.currentTimeMillis();
        Process process = null;
        try {
            process = new ProcessBuilder("java", "-jar", jarPath)
                    .redirectErrorStream(true)
                    .start();

            String output = readOutput(process);
            int exitCode = process.waitFor();

            ProcessInfo info = ProcessInfo.completed(exitCode, output, elapsedSince(startTime));
            log.info("Процесс завершен с кодом: {}, время: {}ms",
                    info.getExitCode(), info.getExecutionTime());
            return info;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyQuietly(process);
            log.error("Прерывание при ожидании процесса: {}", e.getMessage(), e);
            return ProcessInfo.failed(e.getMessage(), elapsedSince(startTime));
        } catch (IOException e) {
            log.error("Ошибка при запуске процесса: {}", e.getMessage(), e);
            return ProcessInfo.failed(e.getMessage(), elapsedSince(startTime));
        }
    }

    private String readOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                log.info("Updater output: {}", line);
            }
        }
        return output.toString();
    }

    private void destroyQuietly(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private long elapsedSince(long startTime) {
        return System.currentTimeMillis() - startTime;
    }

    public boolean checkFilesExist() {
        boolean updaterExists = Files.exists(Paths.get(updaterPath));
        boolean appExists = Files.exists(Paths.get(applicationPath));
        log.info("Updater exists: {}", updaterExists);
        log.info("Application exists: {}", appExists);
        return appExists;
    }
}