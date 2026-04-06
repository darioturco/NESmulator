package com.nes.memory.mapper;

/**
 * Mapper 3 — CNROM.
 *
 * Extremely simple CHR bank-switching mapper. A single write to
 * $8000–$FFFF selects which 8 KB CHR-ROM bank is visible to the PPU.
 * PRG-ROM is fixed (identical behaviour to NROM).
 *
 * CPU memory map:
 *   $8000–$BFFF  First 16 KB PRG bank (fixed)
 *   $C000–$FFFF  Last  16 KB PRG bank (fixed, or mirror of first for 16 KB ROMs)
 *
 * PPU memory map:
 *   $0000–$1FFF  8 KB CHR-ROM bank selected by register (bits 1–0)
 *
 * Games: Paperboy, Gradius, Solomon's Key, Arkanoid, Donkey Kong Classics.
 */
public class MapperCNROM implements Mapper {

    private final byte[] prgRom;
    private final byte[] chrRom;

    /** Currently selected 8 KB CHR bank (0–3). */
    private int chrBank = 0;

    private final int chrBankCount;

    public MapperCNROM(byte[] prgRom, byte[] chrRom, boolean battery) {
        this.prgRom       = prgRom;
        // CNROM always ships CHR-ROM; allocate 8 KB fallback just in case
        this.chrRom       = (chrRom != null) ? chrRom : new byte[0x2000];
        this.chrBankCount = Math.max(1, this.chrRom.length / 0x2000);
    }

    // =========================================================================
    // CPU side
    // =========================================================================

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x8000) {
            return prgRom[addr & (prgRom.length - 1)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int data) {
        if (addr >= 0x8000) {
            chrBank = (data & 0x03) % chrBankCount;
        }
    }

    // =========================================================================
    // PPU side
    // =========================================================================

    @Override
    public int ppuRead(int addr) {
        return chrRom[chrBank * 0x2000 + (addr & 0x1FFF)] & 0xFF;
    }

    @Override
    public void ppuWrite(int addr, int data) {
        // CHR-ROM is read-only
    }
}
