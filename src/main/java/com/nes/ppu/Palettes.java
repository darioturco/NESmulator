package com.nes.ppu;

/**
 * NES palette RAM (32 bytes at PPU $3F00–$3F1F).
 * Holds 4 background palettes and 4 sprite palettes of 4 colours each.
 * Converts 6-bit NES palette indices to 32-bit ARGB values.
 */
public class Palettes {

    /** 32-byte internal palette RAM ($3F00–$3F1F). */
    private final int[] ram = new int[32];

    /**
     * Master NES colour table: 64 entries mapping 6-bit indices to ARGB.
     * Populated with the standard NTSC palette.
     */
    private static final int[] COLOUR_TABLE = new int[64];

    static {
        // TODO: populate with the standard NTSC NES palette
    }

    public Palettes() {
    }

    /**
     * Read a palette RAM entry (mirrors $3F10, $3F14, $3F18, $3F1C to $3F00…).
     *
     * @param address PPU address in the range $3F00–$3F1F
     * @return 6-bit palette index
     */
    public int read(int address) {
        return 0;
    }

    /**
     * Write a 6-bit palette index into palette RAM.
     *
     * @param address PPU address in the range $3F00–$3F1F
     * @param value   6-bit colour index (0x00–0x3F)
     */
    public void write(int address, int value) {
    }

    /**
     * Resolve a 6-bit NES colour index to a 32-bit ARGB value.
     *
     * @param index 6-bit NES palette index
     * @return ARGB colour
     */
    public int toArgb(int index) {
        return COLOUR_TABLE[index & 0x3F];
    }

    /** Reset palette RAM to zeroes. */
    public void reset() {
    }
}
