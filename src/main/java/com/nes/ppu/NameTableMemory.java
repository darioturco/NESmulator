package com.nes.ppu;

import com.nes.memory.Cartridge;
import java.util.Arrays;

/**
 * Nametable memory (internal VRAM) for the PPU bus, covering $2000–$2FFF.
 * Addresses in $3000–$3EFF are mirrors of this range and are resolved by PPUBus
 * before reaching this class.
 *
 * The NES contains 2KB of internal VRAM organised as two physical 1KB banks.
 * These banks are mapped to four logical nametables ($2000, $2400, $2800, $2C00)
 * according to the cartridge's mirroring mode:
 *
 *   HORIZONTAL  – NT0 &amp; NT1 → bank 0 ;  NT2 &amp; NT3 → bank 1
 *   VERTICAL    – NT0 &amp; NT2 → bank 0 ;  NT1 &amp; NT3 → bank 1
 *   SINGLE_LO   – all four nametables → bank 0
 *   SINGLE_HI   – all four nametables → bank 1
 *
 * Each nametable is 960 bytes of tile indices followed by 64 bytes of attribute
 * data (1KB total per table).
 */
public class NameTableMemory {

    /** Two physical 1KB nametable banks. */
    private final int[][] banks = new int[2][0x400];

    private Cartridge cartridge;

    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    /**
     * Read one byte from nametable VRAM.
     *
     * @param addr PPU address in $2000–$2FFF (or $3000–$3EFF already remapped)
     * @return byte value [0, 255]
     */
    public int read(int addr) {
        return resolveBank(addr)[addr & 0x3FF];
    }

    /**
     * Write one byte to nametable VRAM.
     *
     * @param addr PPU address in $2000–$2FFF (or $3000–$3EFF already remapped)
     * @param data byte value [0, 255]
     */
    public void write(int addr, int data) {
        resolveBank(addr)[addr & 0x3FF] = data & 0xFF;
    }

    /** Reset all nametable VRAM to zero. */
    public void reset() {
        for (int[] bank : banks) {
            Arrays.fill(bank, 0);
        }
    }

    // -------------------------------------------------------------------------
    // Save state
    // -------------------------------------------------------------------------

    public int[][] captureBanks() {
        int[][] copy = new int[2][0x400];
        for (int i = 0; i < 2; i++) System.arraycopy(banks[i], 0, copy[i], 0, 0x400);
        return copy;
    }

    public void restoreBanks(int[][] saved) {
        for (int i = 0; i < 2; i++) System.arraycopy(saved[i], 0, banks[i], 0, 0x400);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Map a PPU address to one of the two physical 1KB banks using the
     * cartridge's current mirror mode.
     *
     * The logical nametable index is bits 11–10 of the address (after stripping
     * the $2000 base), giving values 0–3.
     */
    private int[] resolveBank(int addr) {
        // Logical nametable 0–3
        int table = (addr >> 10) & 0x03;

        Cartridge.MirrorMode mode = (cartridge != null)
                ? cartridge.getMirrorMode()
                : Cartridge.MirrorMode.VERTICAL;

        switch (mode) {
            case HORIZONTAL:
                // 0,1 → bank 0 ; 2,3 → bank 1
                return banks[table >> 1];
            case VERTICAL:
                // 0,2 → bank 0 ; 1,3 → bank 1
                return banks[table & 1];
            case SINGLE_SCREEN_LO:
                return banks[0];
            case SINGLE_SCREEN_HI:
                return banks[1];
            default:
                // FOUR_SCREEN would need extra cartridge RAM; fall back to vertical
                return banks[table & 1];
        }
    }
}
