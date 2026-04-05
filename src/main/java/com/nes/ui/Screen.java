package com.nes.ui;

import com.nes.ppu.PPU;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Swing panel that displays the PPU frame buffer at an integer scale factor.
 *
 * The NES outputs 256×240 pixels.  The panel scales that rectangle to fill
 * the window using nearest-neighbour interpolation (no blurring) so pixels
 * stay crisp.
 *
 * Usage:
 * <pre>
 *   Screen screen = new Screen(2);        // 2× → 512×480
 *   Screen.openWindow("NES", screen);     // shows the JFrame
 *   screen.updateFrame(ppu.getFrameBuffer());
 * </pre>
 */
public class Screen extends JPanel {

    /** Native NES resolution. */
    public static final int NES_W = PPU.SCREEN_WIDTH;
    public static final int NES_H = PPU.SCREEN_HEIGHT;

    private final int scale;

    /**
     * Intermediate image written with PPU ARGB data then drawn scaled.
     * TYPE_INT_ARGB matches the int[] layout produced by PaletteMemory.toArgb().
     */
    private final BufferedImage image =
            new BufferedImage(NES_W, NES_H, BufferedImage.TYPE_INT_ARGB);

    /**
     * @param scale integer scale factor (1 = 256×240, 2 = 512×480, 3 = 768×720 …)
     */
    public Screen(int scale) {
        this.scale = Math.max(1, scale);
        setPreferredSize(new Dimension(NES_W * this.scale, NES_H * this.scale));
        setBackground(Color.BLACK);
    }

    // -------------------------------------------------------------------------
    // Frame update
    // -------------------------------------------------------------------------

    /**
     * Copy a PPU frame buffer into the panel and schedule a repaint.
     *
     * @param argbPixels array of {@code NES_W * NES_H} ARGB ints
     *                   (as returned by {@link PPU#getFrameBuffer()})
     */
    public void updateFrame(int[] argbPixels) {
        image.setRGB(0, 0, NES_W, NES_H, argbPixels, 0, NES_W);
        repaint();
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        // Nearest-neighbour: keeps pixel-art style without blurring
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
    }

    // -------------------------------------------------------------------------
    // Window helpers
    // -------------------------------------------------------------------------

    /**
     * Create and show a {@link JFrame} containing {@code screen}.
     * Must be called on the Event Dispatch Thread (or during startup before
     * the EDT becomes active).
     *
     * @param title  window title
     * @param screen the screen panel to embed
     * @return the created frame
     */
    public static JFrame openWindow(String title, Screen screen) {
        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.add(screen);
        frame.pack();
        frame.setLocationRelativeTo(null); // center on screen
        frame.setVisible(true);
        return frame;
    }
}
