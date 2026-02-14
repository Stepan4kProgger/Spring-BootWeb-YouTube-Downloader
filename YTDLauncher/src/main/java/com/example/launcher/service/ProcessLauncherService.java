package com.example.launcher.service;

import com.example.launcher.model.ProcessInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ProcessLauncherService {

    @Value("${app.updater.path:./updater/updater.jar}")
    private String updaterPath;

    @Value("${app.application.path:./application/demo.jar}")
    private String applicationPath;

    @Value("${app.updater.timeout:30000}")
    private long updaterTimeout;

    public ProcessLauncherService() {
        // Пустой конструктор для Spring
    }

    public boolean runUpdater() {
        if (!Files.exists(Paths.get(updaterPath))) {
            log.warn("Updater не найден: {}. Пропускаем обновление.", updaterPath);
            return true; // Продолжаем работу, т.к. updater опционален
        }

        log.info("Запуск updater: {}", updaterPath);
        ProcessInfo result = runJarProcess(updaterPath, updaterTimeout);

        if (!result.isSuccess()) {
            log.warn("Updater завершился с ошибкой (код: {}), но продолжаем запуск",
                    result.getExitCode());
        }

        return result.isSuccess();
    }

    public boolean runApplication() {
        if (!Files.exists(Paths.get(applicationPath))) {
            log.error("Основное приложение не найдено: {}", applicationPath);
            return false;
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "java", "-jar", applicationPath
            );

            processBuilder.inheritIO();
            Process process = processBuilder.start();

            log.info("Основное приложение запущено успешно");
            return true;

        } catch (IOException e) {
            log.error("Ошибка при запуске основного приложения: {}", e.getMessage(), e);
            return false;
        }
    }

    private ProcessInfo runJarProcess(String jarPath, long timeoutMs) {
        ProcessInfo processInfo = new ProcessInfo();
        long startTime = System.currentTimeMillis();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "java", "-jar", jarPath
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.info("Process output: {}", line);
                }
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            if (finished) {
                processInfo.setExitCode(process.exitValue());
                processInfo.setSuccess(process.exitValue() == 0);
            } else {
                process.destroy();
                processInfo.setExitCode(-2);
                processInfo.setSuccess(false);
                output.append("\nProcess timeout exceeded");
            }

            processInfo.setOutput(output.toString());
            processInfo.setExecutionTime(System.currentTimeMillis() - startTime);

            log.info("Процесс завершен с кодом: {}, время: {}ms",
                    processInfo.getExitCode(), processInfo.getExecutionTime());

        } catch (IOException | InterruptedException e) {
            log.error("Ошибка при запуске процесса: {}", e.getMessage(), e);
            processInfo.setExitCode(-1);
            processInfo.setSuccess(false);
            processInfo.setOutput("Error: " + e.getMessage());
            Thread.currentThread().interrupt();
        }

        return processInfo;
    }

    public boolean checkFilesExist() {
        boolean updaterExists = Files.exists(Paths.get(updaterPath));
        boolean appExists = Files.exists(Paths.get(applicationPath));

        log.info("Updater exists: {}", updaterExists);
        log.info("Application exists: {}", appExists);

        return appExists; // Главное - наличие основного приложения
    }
}