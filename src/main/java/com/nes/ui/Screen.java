package com.nes.ui;

import com.nes.memory.Controller;
import com.nes.ppu.PPU;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Swing panel that displays the PPU frame buffer at an integer scale factor.
 * A controller sidebar is drawn to the right of the NES screen.
 */
public class Screen extends JPanel {

    public static final int NES_W = PPU.SCREEN_WIDTH;
    public static final int NES_H = PPU.SCREEN_HEIGHT;

    private final int scale;
    private final int sidebarW;   // width of the controller panel in pixels

    private final BufferedImage image =
            new BufferedImage(NES_W, NES_H, BufferedImage.TYPE_INT_ARGB);

    private volatile int  controllerState = 0;
    private volatile long masterClock     = 0;

    public Screen(int scale) {
        this.scale    = Math.max(1, scale);
        this.sidebarW = this.scale * 44;   // ~132 px at 3×
        setPreferredSize(new Dimension(NES_W * this.scale + sidebarW, NES_H * this.scale));
        setBackground(new Color(30, 30, 30));
        setFocusable(false);  // keep keyboard focus on the JFrame
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    public void updateFrame(int[] argbPixels) {
        image.setRGB(0, 0, NES_W, NES_H, argbPixels, 0, NES_W);
        repaint();
    }

    public void updateController(int buttonState) {
        controllerState = buttonState;
        repaint();
    }

    public void updateClock(long ticks) {
        masterClock = ticks;
        repaint();
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        // NES screen (left portion)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(image, 0, 0, NES_W * scale, NES_H * scale, null);

        // Sidebar (right portion)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        drawSidebar(g2, NES_W * scale, 0, sidebarW, NES_H * scale);
    }

    // -------------------------------------------------------------------------
    // Controller sidebar
    // -------------------------------------------------------------------------

    private static final Color SIDEBAR_BG  = new Color(25, 25, 25);
    private static final Color BTN_OFF     = new Color(70, 70, 70);
    private static final Color BTN_ON      = new Color(230, 230, 60);
    private static final Color DPAD_OFF    = new Color(55, 55, 55);
    private static final Color DPAD_ON     = new Color(60, 200, 60);
    private static final Color A_ON        = new Color(220, 60,  60);
    private static final Color B_ON        = new Color(60,  100, 220);
    private static final Color LABEL_COLOR = new Color(180, 180, 180);
    private static final Color DIM_COLOR   = new Color(100, 100, 100);

    private void drawSidebar(Graphics2D g2, int x, int y, int w, int h) {
        // Background
        g2.setColor(SIDEBAR_BG);
        g2.fillRect(x, y, w, h);

        // Divider line
        g2.setColor(new Color(60, 60, 60));
        g2.fillRect(x, y, 2, h);

        int cx = x + w / 2;           // horizontal centre of sidebar
        int unit = w / 6;             // base unit (~22 px at 3×)

        // Title
        g2.setColor(LABEL_COLOR);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, unit));
        drawCentred(g2, "P1", cx, y + unit * 2);

        // ── D-Pad ──────────────────────────────────────────────
        int dpY = y + h / 4;

        drawArrow(g2, cx,          dpY - unit, 0,
                isPressed(Controller.BTN_UP)    ? DPAD_ON : DPAD_OFF, unit);
        drawArrow(g2, cx,          dpY + unit, 2,
                isPressed(Controller.BTN_DOWN)  ? DPAD_ON : DPAD_OFF, unit);
        drawArrow(g2, cx - unit,   dpY,        3,
                isPressed(Controller.BTN_LEFT)  ? DPAD_ON : DPAD_OFF, unit);
        drawArrow(g2, cx + unit,   dpY,        1,
                isPressed(Controller.BTN_RIGHT) ? DPAD_ON : DPAD_OFF, unit);

        // Centre pip
        g2.setColor(new Color(40, 40, 40));
        int pip = unit / 3;
        g2.fillRect(cx - pip, dpY - pip, pip * 2, pip * 2);

        // D-pad key labels
        g2.setColor(DIM_COLOR);
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, unit * 2 / 3));
        drawCentred(g2, "↑",  cx,          dpY - unit * 2);
        drawCentred(g2, "↓",  cx,          dpY + unit * 2 + unit / 2);
        drawCentred(g2, "←",  cx - unit * 2, dpY + unit / 3);
        drawCentred(g2, "→",  cx + unit * 2, dpY + unit / 3);

        // ── SELECT / START ─────────────────────────────────────
        int midY = y + h / 2 + unit;
        int ow   = unit * 2;
        int oh   = unit;

        drawOval(g2, cx - unit * 2, midY, ow, oh,
                isPressed(Controller.BTN_SELECT) ? BTN_ON : BTN_OFF, "SEL", unit);
        drawOval(g2, cx + unit,     midY, ow, oh,
                isPressed(Controller.BTN_START)  ? BTN_ON : BTN_OFF, "STA", unit);

        // key labels under the ovals
        g2.setColor(DIM_COLOR);
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, unit * 2 / 3));
        drawCentred(g2, "Q", cx - unit * 2, midY + oh + unit * 2 / 3);
        drawCentred(g2, "W", cx + unit,     midY + oh + unit * 2 / 3);

        // ── B / A buttons ──────────────────────────────────────
        int abY  = y + h * 3 / 4 + unit;
        int brad = unit;

        drawCircle(g2, cx - unit, abY, brad,
                isPressed(Controller.BTN_B) ? B_ON : BTN_OFF, "B", unit);
        drawCircle(g2, cx + unit, abY, brad,
                isPressed(Controller.BTN_A) ? A_ON : BTN_OFF, "A", unit);

        // key labels under the circles
        g2.setColor(DIM_COLOR);
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, unit * 2 / 3));
        drawCentred(g2, "Z", cx - unit, abY + brad + unit * 2 / 3);
        drawCentred(g2, "X", cx + unit, abY + brad + unit * 2 / 3);

        // ── Clock counter ──────────────────────────────────────
        int clockY = y + h - unit * 2;
        g2.setColor(new Color(50, 50, 50));
        g2.fillRoundRect(x + unit / 2, clockY - unit / 2,
                         w - unit, unit * 2, unit / 2, unit / 2);

        g2.setColor(new Color(80, 220, 120));
        g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(7, unit * 2 / 3)));
        drawCentred(g2, "CLK", cx, clockY + unit / 4);

        g2.setColor(new Color(180, 255, 180));
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(6, unit / 2)));
        // Format: show value in millions (M) if large enough, else raw
        String clockStr = formatClock(masterClock);
        drawCentred(g2, clockStr, cx, clockY + unit);
    }

    private static String formatClock(long ticks) {
        if (ticks >= 1_000_000_000L) {
            return String.format("%.2fB", ticks / 1_000_000_000.0);
        } else if (ticks >= 1_000_000L) {
            return String.format("%.2fM", ticks / 1_000_000.0);
        } else if (ticks >= 1_000L) {
            return String.format("%.1fK", ticks / 1_000.0);
        } else {
            return String.valueOf(ticks);
        }
    }

    // -------------------------------------------------------------------------
    // Drawing helpers
    // -------------------------------------------------------------------------

    /** dir: 0=up  1=right  2=down  3=left */
    private void drawArrow(Graphics2D g2, int cx, int cy, int dir, Color c, int s) {
        int h = s * 3 / 4;
        int[] xp, yp;
        switch (dir) {
            case 0:  xp = new int[]{cx,      cx - h,   cx + h  }; yp = new int[]{cy - h,  cy + h/2, cy + h/2}; break;
            case 1:  xp = new int[]{cx + h,  cx - h/2, cx - h/2}; yp = new int[]{cy,      cy - h,   cy + h  }; break;
            case 2:  xp = new int[]{cx,      cx - h,   cx + h  }; yp = new int[]{cy + h,  cy - h/2, cy - h/2}; break;
            default: xp = new int[]{cx - h,  cx + h/2, cx + h/2}; yp = new int[]{cy,      cy - h,   cy + h  }; break;
        }
        g2.setColor(c);
        g2.fillPolygon(xp, yp, 3);
    }

    private void drawOval(Graphics2D g2, int cx, int cy, int w, int h, Color c, String label, int unit) {
        g2.setColor(c);
        g2.fillRoundRect(cx - w / 2, cy - h / 2, w, h, h, h);
        g2.setColor(Color.BLACK);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, unit / 2)));
        drawCentred(g2, label, cx, cy + g2.getFontMetrics().getAscent() / 2 - 1);
    }

    private void drawCircle(Graphics2D g2, int cx, int cy, int r, Color c, String label, int unit) {
        g2.setColor(c);
        g2.fillOval(cx - r, cy - r, r * 2, r * 2);
        g2.setColor(Color.BLACK);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(7, unit * 2 / 3)));
        drawCentred(g2, label, cx, cy + g2.getFontMetrics().getAscent() / 2 - 1);
    }

    private void drawCentred(Graphics2D g2, String text, int cx, int cy) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, cx - fm.stringWidth(text) / 2, cy);
    }

    private boolean isPressed(int button) {
        return (controllerState & (1 << button)) != 0;
    }

    // -------------------------------------------------------------------------
    // Window helper
    // -------------------------------------------------------------------------

    public static JFrame openWindow(String title, Screen screen) {
        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setFocusable(true);
        frame.add(screen);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.requestFocusInWindow();
        return frame;
    }
}
