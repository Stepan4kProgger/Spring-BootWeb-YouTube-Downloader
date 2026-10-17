package com.example.launcher.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProcessInfo {
    private int exitCode;
    private String output;
    private boolean success;
    private long executionTime;

    public static ProcessInfo completed(int exitCode, String output, long executionTime) {
        return ProcessInfo.builder()
                .exitCode(exitCode)
                .success(exitCode == 0)
                .output(output)
                .executionTime(executionTime)
                .build();
    }

    public static ProcessInfo failed(String message, long executionTime) {
        return ProcessInfo.builder()
                .exitCode(-1)
                .success(false)
                .output("Error: " + message)
                .executionTime(executionTime)
                .build();
    }
}