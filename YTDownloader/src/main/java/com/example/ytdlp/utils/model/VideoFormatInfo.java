package com.example.ytdlp.utils.model;

import lombok.Data;

@Data
public class VideoFormatInfo {
    private String format_id;
    private String ext;
    private String vcodec;
    private String acodec;
    private Integer height;
    private Integer width;
    private String format;
    private String format_note;
    private Integer abr;
    private String url;
    private String protocol;
}