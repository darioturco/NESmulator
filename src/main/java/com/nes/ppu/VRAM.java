package com.nes.ppu;

/**
 * NES Video RAM (2KB internal VRAM).
 * Stores nametables and attribute tables; addressable in the PPU $2000–$2FFF range.
 * Mirroring mode (horizontal, vertical, four-screen) determines how the two
 * physical 1KB banks map to the four logical nametable addresses.
 */
public class VRAM {

    /** Two physical 1KB nametable banks. */
    private final int[][] banks = new int[2][0x400];

    public VRAM() {
    }

    /**
     * Read a byte from VRAM at the given PPU address ($2000–$2FFF).
     *
     * @param address PPU address
     * @return byte value (0x00–0xFF)
     */
    public int read(int address) {
        return 0;
    }

    /**
     * Write a byte to VRAM at the given PPU address ($2000–$2FFF).
     *
     * @param address PPU address
     * @param value   byte value (0x00–0xFF)
     */
    public void write(int address, int value) {
    }

    /** Reset VRAM to all zeroes. */
    public void reset() {
    }
}
