package com.example.updater.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AppVersions {
    private String jarVersion;
    private String ytDlpVersion;
    private LocalDateTime lastChecked;

    public AppVersions() {
        this.lastChecked = LocalDateTime.now();
    }
}