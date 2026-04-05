package com.nes.ppu;

import com.nes.memory.Cartridge;

/**
 * Pattern memory for the PPU bus, covering $0000–$1FFF (14-bit PPU address space).
 *
 * Pattern Table 0 occupies $0000–$0FFF; Pattern Table 1 occupies $1000–$1FFF.
 * Each 8×8 tile is encoded as two 8-byte bit-planes packed end-to-end (16 bytes/tile).
 *
 * The actual storage lives in the cartridge's CHR-ROM (read-only) or CHR-RAM
 * (read/write, used by some mappers).  This class is a thin routing layer.
 */
public class PatternMemory {

    private Cartridge cartridge;

    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    /**
     * Read one byte from pattern tables.
     *
     * @param addr PPU address in $0000–$1FFF
     * @return byte value [0, 255]
     */
    public int read(int addr) {
        return cartridge != null ? cartridge.ppuRead(addr & 0x1FFF) : 0;
    }

    /**
     * Write one byte to pattern tables (effective only when using CHR-RAM).
     *
     * @param addr PPU address in $0000–$1FFF
     * @param data byte value [0, 255]
     */
    public void write(int addr, int data) {
        if (cartridge != null) {
            cartridge.ppuWrite(addr & 0x1FFF, data);
        }
    }
}
