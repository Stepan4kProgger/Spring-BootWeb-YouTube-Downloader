package com.example.ytdlp.ui;

import java.awt.*;
import java.util.Arrays;
import java.util.HashSet;

public final class Theme {
    private Theme() {}

    public static volatile boolean dark = true;

    public static final String FONT = detectFont();

    private static String detectFont() {
        String[] preferred = {"Inter", "SF Pro Text", "Segoe UI", "Roboto",
                "Helvetica Neue", "Cantarell", "SansSerif"};
        var available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : preferred) if (available.contains(f)) return f;
        return "SansSerif";
    }

    // Light
    public static final Color LIGHT_BG            = new Color(0xFA, 0xFA, 0xFA);
    public static final Color LIGHT_CARD          = new Color(0xFF, 0xFF, 0xFF);
    public static final Color LIGHT_CARD_HOVER    = new Color(0xF4, 0xF4, 0xF5);
    public static final Color LIGHT_TEXT          = new Color(0x0A, 0x0A, 0x0A);
    public static final Color LIGHT_TEXT_MUTED    = new Color(0x71, 0x71, 0x7A);
    public static final Color LIGHT_BORDER        = new Color(0xE4, 0xE4, 0xE7);
    public static final Color LIGHT_PRIMARY       = new Color(0x18, 0x18, 0x1B);
    public static final Color LIGHT_PRIMARY_HOVER = new Color(0x27, 0x27, 0x2A);
    public static final Color LIGHT_PRIMARY_PRESS = new Color(0x3F, 0x3F, 0x46);
    public static final Color LIGHT_ON_PRIMARY    = new Color(0xFA, 0xFA, 0xFA);
    public static final Color LIGHT_SECONDARY     = new Color(0x71, 0x71, 0x7A);
    public static final Color LIGHT_SUCCESS       = new Color(0x16, 0xA3, 0x4A);
    public static final Color LIGHT_DANGER        = new Color(0xDC, 0x26, 0x26);
    public static final Color LIGHT_WARNING       = new Color(0xD9, 0x77, 0x06);
    public static final Color LIGHT_INFO          = new Color(0x25, 0x63, 0xEB);
    public static final Color LIGHT_FIELD_BG      = new Color(0xFF, 0xFF, 0xFF);
    public static final Color LIGHT_PROGRESS_BG   = new Color(0xF4, 0xF4, 0xF5);

    // Dark
    public static final Color DARK_BG             = new Color(0x09, 0x09, 0x0B);
    public static final Color DARK_CARD           = new Color(0x18, 0x18, 0x1B);
    public static final Color DARK_CARD_HOVER     = new Color(0x1F, 0x1F, 0x23);
    public static final Color DARK_TEXT           = new Color(0xFA, 0xFA, 0xFA);
    public static final Color DARK_TEXT_MUTED     = new Color(0xA1, 0xA1, 0xAA);
    public static final Color DARK_BORDER         = new Color(0x27, 0x27, 0x2A);
    public static final Color DARK_PRIMARY        = new Color(0xFA, 0xFA, 0xFA);
    public static final Color DARK_PRIMARY_HOVER  = new Color(0xE4, 0xE4, 0xE7);
    public static final Color DARK_PRIMARY_PRESS  = new Color(0xD4, 0xD4, 0xD7);
    public static final Color DARK_ON_PRIMARY     = new Color(0x0A, 0x0A, 0x0A);
    public static final Color DARK_SECONDARY      = new Color(0xA1, 0xA1, 0xAA);
    public static final Color DARK_SUCCESS        = new Color(0x22, 0xC5, 0x5E);
    public static final Color DARK_DANGER         = new Color(0xF8, 0x71, 0x71);
    public static final Color DARK_WARNING        = new Color(0xFB, 0xBF, 0x24);
    public static final Color DARK_INFO           = new Color(0x60, 0xA5, 0xFA);
    public static final Color DARK_FIELD_BG       = new Color(0x0F, 0x0F, 0x12);
    public static final Color DARK_PROGRESS_BG    = new Color(0x27, 0x27, 0x2A);

    public static Color bg()           { return dark ? DARK_BG : LIGHT_BG; }
    public static Color card()         { return dark ? DARK_CARD : LIGHT_CARD; }
    public static Color cardHover()    { return dark ? DARK_CARD_HOVER : LIGHT_CARD_HOVER; }
    public static Color text()         { return dark ? DARK_TEXT : LIGHT_TEXT; }
    public static Color textMuted()    { return dark ? DARK_TEXT_MUTED : LIGHT_TEXT_MUTED; }
    public static Color border()       { return dark ? DARK_BORDER : LIGHT_BORDER; }
    public static Color primary()      { return dark ? DARK_PRIMARY : LIGHT_PRIMARY; }
    public static Color primaryHover() { return dark ? DARK_PRIMARY_HOVER : LIGHT_PRIMARY_HOVER; }
    public static Color primaryPress() { return dark ? DARK_PRIMARY_PRESS : LIGHT_PRIMARY_PRESS; }
    public static Color onPrimary()    { return dark ? DARK_ON_PRIMARY : LIGHT_ON_PRIMARY; }
    public static Color secondary()    { return dark ? DARK_SECONDARY : LIGHT_SECONDARY; }
    public static Color success()      { return dark ? DARK_SUCCESS : LIGHT_SUCCESS; }
    public static Color danger()       { return dark ? DARK_DANGER : LIGHT_DANGER; }
    public static Color warning()      { return dark ? DARK_WARNING : LIGHT_WARNING; }
    public static Color info()         { return dark ? DARK_INFO : LIGHT_INFO; }
    public static Color fieldBg()      { return dark ? DARK_FIELD_BG : LIGHT_FIELD_BG; }
    public static Color progressBg()   { return dark ? DARK_PROGRESS_BG : LIGHT_PROGRESS_BG; }
}