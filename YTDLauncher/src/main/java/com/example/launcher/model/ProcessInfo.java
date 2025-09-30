package com.example.launcher.model;

import lombok.Data;

@Data
public class ProcessInfo {
    private int exitCode;
    private String output;
    private boolean success;
    private long executionTime;
}