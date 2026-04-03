package com.nes.ppu;

/**
 * Handles rendering of NES background tiles and sprites into the frame buffer.
 * Reads pattern tables (CHR-ROM) and nametables to produce 256x240 pixel frames.
 */
public class Graphics {

    /** Output frame buffer: 256 * 240 ARGB pixels. */
    private final int[] frameBuffer = new int[256 * 240];

    public Graphics() {
    }

    /**
     * Render a single 8x8 background tile at the given nametable position.
     *
     * @param tileX  column index (0–31)
     * @param tileY  row index (0–29)
     * @param tileId pattern table tile index
     */
    public void renderTile(int tileX, int tileY, int tileId) {
    }

    /**
     * Render a sprite (OAM entry) at its (x, y) screen position.
     *
     * @param x      sprite X position
     * @param y      sprite Y position
     * @param tileId pattern table tile index
     * @param attrs  OAM attribute byte (palette, flip flags, priority)
     */
    public void renderSprite(int x, int y, int tileId, int attrs) {
    }

    /** Returns the completed frame buffer. */
    public int[] getFrameBuffer() {
        return frameBuffer;
    }

    /** Clear the frame buffer to black. */
    public void clearFrame() {
    }
}
