package com.example.ytdlp.utils.constants;

import java.util.regex.Pattern;

public class RegexPatterns {
    public static final String FILE_EXTENSIONS = "(mp4|mp3|webm|m4a|ogg|wav|flv|avi|mov|wmv|mkv)";
    public static final Pattern PROGRESS_PATTERN = Pattern.compile("\\[download]\\s+(\\d+\\.?\\d*)%");
    public static final Pattern DESTINATION_PATTERN = Pattern.compile("Destination:\\s*([^\\n]+)");
    public static final Pattern ALREADY_DOWNLOADED_PATTERN = Pattern.compile(
            "([a-zA-Z]:\\\\[^\\n]+?\\." + FILE_EXTENSIONS + "|/[^\\n]+?\\." + FILE_EXTENSIONS + ")");
    public static final Pattern GENERAL_FILE_PATTERN = Pattern.compile("(\\S+\\." + FILE_EXTENSIONS + ")");
    public static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("(" +
            "[a-zA-Z]:\\\\[^\\n]+?\\." + FILE_EXTENSIONS + ")");
}