package com.example.ytdlp.ui;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class SwingUtils {
    private SwingUtils() {}

    public static final int RADIUS = 10;
    public static final float TEXT_SCALE = 1.25f;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public static Font font(int style, float baseSize) {
        return new Font(Theme.FONT, style,
                Math.max(10, Math.round(baseSize * TEXT_SCALE)));
    }

    public static String formatDateTime(LocalDateTime dt) {
        return dt == null ? "" : dt.format(DATE_FMT);
    }

    public static JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.BOLD, 12));
        l.setForeground(Theme.text());
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    public static JLabel hintLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.PLAIN, 11));
        l.setForeground(Theme.textMuted());
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    public static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.BOLD, 13));
        l.setForeground(Theme.text());
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    public static javax.swing.border.Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                new RoundedBorder(Theme.border(), RADIUS),
                BorderFactory.createEmptyBorder(16, 18, 16, 18)
        );
    }

    public static void stylePrimaryButton(JButton b)   { installRounded(b, "primary"); }
    public static void styleSecondaryButton(JButton b) { installRounded(b, "secondary"); }
    public static void styleGhostButton(JButton b)     { installRounded(b, "ghost"); }
    public static void styleIconButton(JButton b)      { installRounded(b, "icon"); }

    private static void installRounded(JButton b, String style) {
        b.putClientProperty("style", style);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setRolloverEnabled(true);
        if (!(b.getUI() instanceof RoundedButtonUI)) {
            b.setUI(new RoundedButtonUI());
        }
        Insets pad = switch (style) {
            case "primary", "secondary" -> new Insets(10, 20, 10, 20);
            case "ghost", "icon"        -> new Insets(6, 10, 6, 10);
            default                     -> new Insets(6, 12, 6, 12);
        };
        b.setBorder(BorderFactory.createEmptyBorder(pad.top, pad.left, pad.bottom, pad.right));
        b.setFont(font(Font.BOLD, 13));
        b.repaint();
    }

    public static JTextField textField(int fontSize) {
        JTextField f = new JTextField() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.fieldBg());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        f.setOpaque(false);
        f.setFont(font(Font.PLAIN, fontSize));
        f.setForeground(Theme.text());
        f.setCaretColor(Theme.text());
        f.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(Theme.border(), 8),
                BorderFactory.createEmptyBorder(9, 12, 9, 12)
        ));
        return f;
    }

    public static JPanel roundedCard(Color bg, Color border, int radius, int padding) {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius * 2, radius * 2);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setBackground(bg);
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(border, radius),
                BorderFactory.createEmptyBorder(padding, padding, padding, padding)
        ));
        return p;
    }

    public static JLabel pillLabel(String text, Color bg) {
        JLabel l = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                int r = getHeight();
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), r, r);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setOpaque(false);
        l.setBackground(bg);
        l.setForeground(Color.WHITE);
        l.setFont(font(Font.BOLD, 10));
        l.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
        return l;
    }

    public static JProgressBar progressBar() {
        JProgressBar bar = new JProgressBar(0, 100) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight(), r = h;
                g2.setColor(Theme.progressBg());
                g2.fillRoundRect(0, 0, w, h, r, r);

                int range = getMaximum() - getMinimum();
                double frac = range > 0 ? (double)(getValue() - getMinimum()) / range : 0;
                int fill = (int)(w * frac);
                if (fill > 0) {
                    g2.setColor(Theme.primary());
                    g2.fillRoundRect(0, 0, Math.max(fill, r), h, r, r);
                }

                String s = getString();
                if (s != null && isStringPainted()) {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setFont(getFont());
                    FontMetrics fm = g2.getFontMetrics();
                    int tx = (w - fm.stringWidth(s)) / 2;
                    int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
                    boolean onFill = fill > w / 2;
                    g2.setColor(onFill ? Theme.onPrimary() : Theme.text());
                    g2.drawString(s, tx, ty);
                }
                g2.dispose();
            }
        };
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder());
        bar.setStringPainted(true);
        bar.setFont(font(Font.PLAIN, 10));
        return bar;
    }

    static class RoundedBorder extends AbstractBorder {
        private final Color color;
        private final int radius;
        RoundedBorder(Color c, int r) { this.color = c; this.radius = r; }

        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(x, y, w - 1, h - 1, radius * 2, radius * 2);
            g2.dispose();
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(0, 0, 0, 0); }
        @Override public boolean isBorderOpaque() { return false; }
    }

    public static javax.swing.border.Border roundedBorder(Color color, int radius) {
        return new RoundedBorder(color, radius);
    }

    static class RoundedButtonUI extends BasicButtonUI {
        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            String style = (String) b.getClientProperty("style");
            if (style == null) style = "secondary";

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel m = b.getModel();
            boolean enabled = b.isEnabled();
            Color fill = null, borderColor = null;
            Color targetFg;

            if (!enabled) {
                fill = null;
                borderColor = Theme.border();
                targetFg = Theme.textMuted();
            } else {
                switch (style) {
                    case "primary" -> {
                        fill = m.isPressed() ? Theme.primaryPress()
                                : m.isRollover() ? Theme.primaryHover()
                                : Theme.primary();
                        targetFg = Theme.onPrimary();
                    }
                    case "secondary" -> {
                        fill = m.isRollover() ? Theme.cardHover() : Theme.card();
                        borderColor = Theme.border();
                        targetFg = Theme.text();
                    }
                    default -> {
                        fill = m.isRollover() ? Theme.cardHover() : null;
                        targetFg = Theme.textMuted();
                    }
                }
            }

            if (!targetFg.equals(b.getForeground())) {
                b.setForeground(targetFg);
            }

            if (fill != null) {
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), RADIUS * 2, RADIUS * 2);
            }
            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1,
                        RADIUS * 2, RADIUS * 2);
            }
            g2.dispose();

            super.paint(g, c);
        }
    }
}