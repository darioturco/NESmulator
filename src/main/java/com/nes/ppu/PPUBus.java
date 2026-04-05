package com.nes.ppu;

import com.nes.memory.Cartridge;

/**
 * PPU address bus — routes reads and writes across the PPU's 14-bit address space.
 *
 * Address map ($0000–$3FFF):
 *
 *   $0000–$1FFF  →  Pattern tables 0 &amp; 1  (CHR-ROM/RAM via {@link PatternMemory})
 *   $2000–$2FFF  →  Nametables 0–3        (2KB internal VRAM via {@link NameTableMemory})
 *   $3000–$3EFF  →  Mirror of $2000–$2EFF  (remapped before dispatch)
 *   $3F00–$3F1F  →  Palette RAM            (via {@link PaletteMemory})
 *   $3F20–$3FFF  →  Mirrors of $3F00–$3F1F (remapped before dispatch)
 */
public class PPUBus {

    private final PatternMemory   patternMemory;
    private final NameTableMemory nameTableMemory;
    private final PaletteMemory   paletteMemory;

    public PPUBus() {
        this.patternMemory   = new PatternMemory();
        this.nameTableMemory = new NameTableMemory();
        this.paletteMemory   = new PaletteMemory();
    }

    /** Attach the cartridge so pattern and nametable memory can access CHR data and mirror mode. */
    public void setCartridge(Cartridge cartridge) {
        patternMemory.setCartridge(cartridge);
        nameTableMemory.setCartridge(cartridge);
    }

    // -------------------------------------------------------------------------
    // Bus read / write
    // -------------------------------------------------------------------------

    /**
     * Read one byte from PPU address space.
     *
     * @param addr 14-bit PPU address ($0000–$3FFF)
     * @return byte value [0, 255]
     */
    public int read(int addr) {
        addr &= 0x3FFF;

        if (addr < 0x2000) {
            return patternMemory.read(addr);
        }
        if (addr < 0x3F00) {
            // $3000–$3EFF mirrors $2000–$2EFF: strip bit 12 to fold back
            return nameTableMemory.read(addr & 0x2FFF);
        }
        return paletteMemory.read(addr);
    }

    /**
     * Write one byte to PPU address space.
     *
     * @param addr 14-bit PPU address ($0000–$3FFF)
     * @param data byte value [0, 255]
     */
    public void write(int addr, int data) {
        addr &= 0x3FFF;

        if (addr < 0x2000) {
            patternMemory.write(addr, data);
        } else if (addr < 0x3F00) {
            nameTableMemory.write(addr & 0x2FFF, data);
        } else {
            paletteMemory.write(addr, data);
        }
    }

    // -------------------------------------------------------------------------
    // Convenience
    // -------------------------------------------------------------------------

    /**
     * Resolve a 6-bit NES colour index from palette RAM to a 32-bit ARGB value.
     * Delegates to {@link PaletteMemory#toArgb(int)}.
     */
    public int toArgb(int paletteIndex) {
        return paletteMemory.toArgb(paletteIndex);
    }

    /** Reset internal state (nametable VRAM and palette RAM). */
    public void reset() {
        nameTableMemory.reset();
        paletteMemory.reset();
    }
}
