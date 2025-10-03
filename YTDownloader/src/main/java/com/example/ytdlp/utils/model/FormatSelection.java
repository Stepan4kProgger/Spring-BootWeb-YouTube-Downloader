package com.example.ytdlp.utils.model;

import lombok.Data;

@Data
public class FormatSelection {
    final VideoFormatInfo videoFormat;
    final VideoFormatInfo audioFormat;
    final String videoUrl;
    final String audioUrl;
    final String videoFormatId;
    final String audioFormatId;
    final boolean isCombined;
}