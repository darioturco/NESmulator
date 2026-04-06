package com.nes.ppu;

import java.util.Arrays;

/**
 * Palette RAM for the PPU bus, covering $3F00–$3F1F.
 * Addresses $3F20–$3FFF are mirrors and are resolved by PPUBus before
 * reaching this class.
 *
 * Layout (32 bytes):
 *   $3F00        – Universal background colour
 *   $3F01–$3F03  – Background palette 0, colours 1–3
 *   $3F04        – (unused BG colour slot)
 *   $3F05–$3F07  – Background palette 1, colours 1–3
 *   …            – (repeat for palettes 2 and 3)
 *   $3F10        – Mirror of $3F00 (universal background)
 *   $3F11–$3F13  – Sprite palette 0, colours 1–3
 *   …            – (repeat for sprite palettes 1–3)
 *   $3F14/$3F18/$3F1C – Mirrors of $3F04/$3F08/$3F0C
 *
 * Palette entries are 6-bit indices into the 64-entry NTSC master colour table.
 * The toArgb() method converts a 6-bit index to a 32-bit ARGB value.
 */
public class PaletteMemory {

    /** 32 palette RAM entries; each stores a 6-bit NES colour index. */
    private final int[] ram = new int[32];

    /**
     * Standard NTSC NES master palette – 64 entries mapping 6-bit indices
     * to 32-bit ARGB colours.  Values sourced from the commonly used
     * 2C02 composite palette (Nestopia / fceux reference).
     */
    private static final int[] COLOUR_TABLE = {
        // Row 0 ($00–$0F)
        0xFF626262, 0xFF012EA5, 0xFF260EC5, 0xFF4E09A2,
        0xFF73007B, 0xFF7F0020, 0xFF71120B, 0xFF4E2900,
        0xFF254500, 0xFF005A00, 0xFF006000, 0xFF005D21,
        0xFF00464E, 0xFF000000, 0xFF000000, 0xFF000000,
        // Row 1 ($10–$1F)
        0xFFABABAB, 0xFF106FEE, 0xFF4B4EFF, 0xFF7E30FF,
        0xFFB01DD7, 0xFFBE2277, 0xFFAF3B11, 0xFF875B00,
        0xFF587F00, 0xFF219A00, 0xFF00A300, 0xFF009F51,
        0xFF00889D, 0xFF000000, 0xFF000000, 0xFF000000,
        // Row 2 ($20–$2F)
        0xFFFFFFFF, 0xFF63C0FF, 0xFF9AACFF, 0xFFCC9EFF,
        0xFFF58FFF, 0xFFFD93C7, 0xFFF5A965, 0xFFD6BD05,
        0xFFA8D800, 0xFF71F100, 0xFF47FB2E, 0xFF30F695,
        0xFF34E7EC, 0xFF5E5E5E, 0xFF000000, 0xFF000000,
        // Row 3 ($30–$3F)
        0xFFFFFFFF, 0xFFC5E6FF, 0xFFD5D8FF, 0xFFEAD4FF,
        0xFFF8CEFF, 0xFFFECEEA, 0xFFFAD6BA, 0xFFF0E09E,
        0xFFE0EC9E, 0xFFCBF5A0, 0xFFB8FAB5, 0xFFADF9D1,
        0xFFADF5EF, 0xFFC2C2C2, 0xFF000000, 0xFF000000
    };

    /**
     * Read a 6-bit colour index from palette RAM.
     *
     * @param addr PPU address in $3F00–$3F1F
     * @return 6-bit palette index [0, 63]
     */
    public int read(int addr) {
        return ram[mirror(addr)];
    }

    /**
     * Write a 6-bit colour index into palette RAM.
     *
     * @param addr PPU address in $3F00–$3F1F
     * @param data 6-bit colour index (upper bits ignored)
     */
    public void write(int addr, int data) {
        ram[mirror(addr)] = data & 0x3F;
    }

    /**
     * Convert a 6-bit NES colour index to a 32-bit ARGB value.
     *
     * @param index 6-bit NES palette index
     * @return ARGB colour (alpha = 0xFF)
     */
    public int toArgb(int index) {
        return COLOUR_TABLE[index & 0x3F];
    }

    /** Reset all palette RAM entries to zero. */
    public void reset() {
        Arrays.fill(ram, 0);
    }

    // -------------------------------------------------------------------------
    // Save state
    // -------------------------------------------------------------------------

    public int[] captureRam() { return ram.clone(); }

    public void restoreRam(int[] saved) { System.arraycopy(saved, 0, ram, 0, ram.length); }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Apply the palette address mirror rules and return an index into {@code ram}.
     *
     * The palette window is 32 bytes ($3F00–$3F1F).  Within that window the
     * "background colour" slots at offsets 0x10, 0x14, 0x18 and 0x1C mirror
     * the universal background colour entries at 0x00, 0x04, 0x08 and 0x0C.
     */
    private int mirror(int addr) {
        int offset = addr & 0x1F; // 32-byte window
        // Sprite "transparent" entries mirror background colour slots
        if (offset == 0x10 || offset == 0x14 || offset == 0x18 || offset == 0x1C) {
            offset &= 0x0F;
        }
        return offset;
    }
}
