package com.example.ytdlp.service;

import com.example.ytdlp.config.ApplicationConfig;
import com.example.ytdlp.utils.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class DownloadManagementService {

    /**
     * Предпочитаемый язык аудиодорожки.
     * yt-dlp отдаёт поле {@code language} в формате BCP-47: "ru", "en-US", "de-DE" и т.п.
     * <ul>
     *   <li>"ru"     — брать русскую, если есть; иначе оригинальную;</li>
     *   <li>"en-US"  — брать английскую (US);</li>
     *   <li>null     — не отдавать предпочтения языку, брать только оригинальную (language_preference >= 10).</li>
     * </ul>
     * Значение передаётся в {@link FormatSelectionService#selectFormats} и там учитывается
     * при сортировке аудиоформатов.
     */

    private final VideoInfoService videoInfoService;
    private final FormatSelectionService formatSelectionService;
    private final ProgressTrackingService progressTrackingService;
    private final HistoryService historyService;
    private final TempFileService tempFileService;
    private final FfmpegService ffmpegService;
    private final UtilityService utilityService;

    @Value("${yt-dlp.path}") private String ytDlpPath;
    @Value("${ffmpeg.path}") private String ffmpegPath;

    @Autowired private ApplicationConfig appConfig;

    public DownloadResponse downloadVideo(DownloadRequest request) {
        String downloadId = UUID.randomUUID().toString();

        DownloadProgress progress = progressTrackingService.initializeProgress(downloadId, request);

        try {
            VideoInfo videoInfo = videoInfoService.getVideoInfo(request);

            FormatSelection formats = formatSelectionService.selectFormats(
                    videoInfo,
                    appConfig.getQuality(),
                    appConfig.isCompatibilityMode());

            progress.setTotalStages(formats.isCombined() ? 3 : 5);

            Path finalFile = executeDownload(request, videoInfo, formats, progress);
            return completeDownload(progress, finalFile);

        } catch (Exception e) {
            return handleError(downloadId, progress, e);
        }
    }

    private Path executeDownload(DownloadRequest request, VideoInfo videoInfo,
                                 FormatSelection formats, DownloadProgress progress) throws Exception {
        Path videoTempFile = null;
        Path audioTempFile = null;
        Path combinedTempFile = null;
        Path cookiesFile = null;
        Path finalOutputFile;

        try {
            if (!Files.exists(Paths.get(ytDlpPath))) {
                throw new IOException("yt-dlp not found at: " + ytDlpPath);
            }
            if (!Files.exists(Paths.get(ffmpegPath))) {
                throw new IOException("ffmpeg not found at: " + ffmpegPath);
            }

            Path downloadDir = Paths.get(appConfig.getDirectory());
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir);
            }

            cookiesFile = tempFileService.createCookiesFile(request);

            String finalFilename = utilityService.generateFinalFilenameFromVideoInfo(videoInfo);
            finalOutputFile = downloadDir.resolve(finalFilename);
            if (Files.exists(finalOutputFile)) {
                String baseName = finalFilename.substring(0, finalFilename.lastIndexOf('.'));
                String extension = finalFilename.substring(finalFilename.lastIndexOf('.'));
                finalFilename = baseName + "_" + System.currentTimeMillis() + extension;
                finalOutputFile = downloadDir.resolve(finalFilename);
            }

            if (formats.isCombined()) {
                progressTrackingService.updateStage(progress, "downloading_combined", 2);

                String tempPrefix = "temp_" + progress.getDownloadId() + "_";
                combinedTempFile = Files.createTempFile(downloadDir, tempPrefix + "combined", ".mp4");

                downloadFormat(request.getUrl(), formats.getVideoFormatId(), formats.getVideoUrl(),
                        combinedTempFile.toString(), cookiesFile, progress);

                Files.move(combinedTempFile, finalOutputFile, StandardCopyOption.REPLACE_EXISTING);

            } else {
                String tempPrefix = "temp_" + progress.getDownloadId() + "_";
                String videoExt = determineFileExtension(formats.getVideoFormat(), true);
                String audioExt = determineFileExtension(formats.getAudioFormat(), false);

                videoTempFile = Files.createTempFile(downloadDir, tempPrefix + "video", videoExt);
                audioTempFile = Files.createTempFile(downloadDir, tempPrefix + "audio", audioExt);

                progressTrackingService.updateStage(progress, "downloading_video", 2);
                downloadFormat(request.getUrl(), formats.getVideoFormatId(), formats.getVideoUrl(),
                        videoTempFile.toString(), cookiesFile, progress);

                progressTrackingService.updateStage(progress, "downloading_audio", 3);
                downloadFormat(request.getUrl(), formats.getAudioFormatId(), formats.getAudioUrl(),
                        audioTempFile.toString(), cookiesFile, progress);

                progressTrackingService.updateStage(progress, "merging", 4);
                ffmpegService.mergeWithFfmpeg(videoTempFile.toString(), audioTempFile.toString(),
                        finalOutputFile.toString());
            }

            return finalOutputFile;

        } finally {
            if (formats != null && formats.isCombined()) {
                tempFileService.cleanupTempFiles(combinedTempFile, cookiesFile);
            } else {
                tempFileService.cleanupTempFiles(videoTempFile, audioTempFile, cookiesFile);
            }
        }
    }

    private DownloadResponse completeDownload(DownloadProgress progress, Path finalFile) {
        progressTrackingService.updateStage(progress, "finalizing", progress.getTotalStages());

        progress.setStatus("completed");
        progress.setProgress(100);
        progress.setFilename(finalFile.getFileName().toString());
        progress.setEndTime(LocalDateTime.now());

        historyService.addToDownloadHistory(progress);
        progressTrackingService.removeProgress(progress.getDownloadId());

        return new DownloadResponse(true, "Download completed successfully",
                finalFile.getParent().toString(), progress.getFilename(), null);
    }

    private DownloadResponse handleError(String downloadId, DownloadProgress progress, Exception e) {
        log.error("Download failed: {}", e.getMessage(), e);

        String message = e.getMessage() != null ? e.getMessage() : "Download failed";

        if (progress != null) {
            progress.setStatus("error");
            progress.setErrorMessage(message);
            progress.setEndTime(LocalDateTime.now());

            historyService.addToDownloadHistory(progress);
            progressTrackingService.removeProgress(downloadId);
        }

        return new DownloadResponse(false, null, null, "unknown", message);
    }

    public void downloadFormat(String url, String formatId, String formatUrl, String outputFile,
                               Path cookiesFile, DownloadProgress progress)
            throws IOException, InterruptedException {

        File output = new File(outputFile);
        Path outputFilePath = Paths.get(outputFile);
        if (output.exists() && output.length() == 0) {
            Files.deleteIfExists(outputFilePath);
        }

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add(url);
        command.add("-f");
        command.add(formatId);
        command.add("--no-playlist");
        command.add("--js-runtimes");
        command.add("node");
        command.add("--remote-components");
        command.add("ejs:github");
        command.add("-o");
        command.add(outputFile);
        command.add("--no-overwrites");
        command.add("--continue");
        command.add("--retries");
        command.add("10");

        if (cookiesFile != null) {
            command.add("--cookies");
            command.add(cookiesFile.toString());
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();
        progress.setProcess(process);
        progressTrackingService.getActiveProcesses().put(progress.getDownloadId(), process);

        Thread progressThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("[download]")) {
                        int currentProgress = progressTrackingService.parseProgressFromLine(line);
                        if (currentProgress >= 0) {
                            progress.setProgress(Math.min(currentProgress, 100));
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Error reading download progress: {}", e.getMessage());
            }
        });
        progressThread.start();

        StringBuilder errorBuffer = new StringBuilder();
        Thread errorThread = getErrorThread(process, errorBuffer);

        int exitCode = process.waitFor();
        progressThread.join();
        errorThread.join();
        progressTrackingService.getActiveProcesses().remove(progress.getDownloadId());

        if (exitCode != 0) {
            String firstError = extractFirstError(errorBuffer.toString());
            throw new IOException(firstError != null
                    ? firstError
                    : "yt-dlp exited with code " + exitCode);
        }

        File downloadedFile = new File(outputFile);
        if (!downloadedFile.exists()) {
            throw new IOException("Downloaded file does not exist: " + outputFile);
        }
        if (downloadedFile.length() == 0) {
            Files.deleteIfExists(outputFilePath);
            throw new IOException("Downloaded file is empty (0 bytes): " + outputFile);
        }
    }

    private static Thread getErrorThread(Process process, StringBuilder buffer) {
        Thread errorThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (buffer) {
                        buffer.append(line).append('\n');
                    }
                    log.warn("yt-dlp: {}", line);
                }
            } catch (IOException e) {
                log.error("Error reading download errors: {}", e.getMessage());
            }
        });
        errorThread.start();
        return errorThread;
    }

    private static String extractFirstError(String stderr) {
        if (stderr == null || stderr.isBlank()) return null;
        String[] lines = stderr.split("\\R");
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("ERROR:")) {
                String msg = t.substring("ERROR:".length()).trim();
                if (!msg.isEmpty()) return msg;
            }
        }
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    private String determineFileExtension(VideoFormatInfo format, boolean isVideo) {
        if (format == null) {
            return isVideo ? ".mp4" : ".m4a";
        }

        String ext = format.getExt();
        if (ext != null && !ext.trim().isEmpty()) {
            return "." + ext;
        }

        if (isVideo) {
            String vcodec = format.getVcodec();
            if (vcodec != null && vcodec.contains("vp9")) {
                return ".webm";
            }
            return ".mp4";
        } else {
            String acodec = format.getAcodec();
            if (acodec != null) {
                if (acodec.contains("opus")) {
                    return ".webm";
                } else if (acodec.contains("mp4a") || acodec.contains("aac")) {
                    return ".m4a";
                } else if (acodec.contains("vorbis")) {
                    return ".webm";
                }
            }
            return ".m4a";
        }
    }

    public void resumeDownload(DownloadProgress progress) {
        DownloadRequest request = createRequestFromProgress(progress);
        downloadVideo(request);
    }

    private static Thread getErrorThread(Process process) {
        Thread errorThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.warn("Download error: {}", line);
                    if (line.contains("ERROR") || line.contains("error")) {
                        log.error("Critical download error: {}", line);
                    }
                }
            } catch (IOException e) {
                log.error("Error reading download errors: {}", e.getMessage());
            }
        });
        errorThread.start();
        return errorThread;
    }

    private DownloadRequest createRequestFromProgress(DownloadProgress progress) {
        DownloadRequest request = new DownloadRequest();
        request.setUrl(progress.getUrl());
        return request;
    }
}