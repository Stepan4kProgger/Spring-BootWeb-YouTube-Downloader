package com.example.ytdlp.utils.model;

import com.example.ytdlp.service.YtDlpService;
import lombok.Data;

import java.util.List;

@Data
public class VideoInfo {
    private String title;
    private String id;
    private List<VideoFormatInfo> formats;
}