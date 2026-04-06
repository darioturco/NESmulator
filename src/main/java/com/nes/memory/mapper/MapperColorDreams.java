package com.nes.memory.mapper;

/**
 * Mapper 11 — Color Dreams.
 *
 * A single write to $8000–$FFFF sets both PRG and CHR banks simultaneously.
 * PRG-ROM is switched in 32 KB pages; CHR-ROM in 8 KB pages.
 * Mirroring is fixed (set by the iNES header; typically vertical).
 *
 * CPU memory map:
 *   $8000–$FFFF  Switchable 32 KB PRG bank (bits 3–0 of register)
 *
 * PPU memory map:
 *   $0000–$1FFF  8 KB CHR-ROM bank selected by bits 7–4 of register
 *
 * Register (write to $8000–$FFFF):
 *   bits 3–0  PRG bank number (up to 16 banks = 512 KB)
 *   bits 7–4  CHR bank number (up to 16 banks = 128 KB)
 *
 * Games: Spiritual Warfare, Bible Adventures, Exodus (Wisdom Tree).
 */
public class MapperColorDreams implements Mapper {

    private final byte[] prgRom;
    private final byte[] chrRom;

    private final int prgBankCount;
    private final int chrBankCount;

    private int prgBank = 0;
    private int chrBank = 0;

    public MapperColorDreams(byte[] prgRom, byte[] chrRom, boolean battery) {
        this.prgRom       = prgRom;
        this.chrRom       = (chrRom != null) ? chrRom : new byte[0x2000];
        this.prgBankCount = Math.max(1, prgRom.length / 0x8000);
        this.chrBankCount = Math.max(1, this.chrRom.length / 0x2000);
    }

    // =========================================================================
    // CPU side
    // =========================================================================

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x8000) {
            return prgRom[prgBank * 0x8000 + (addr & 0x7FFF)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int data) {
        if (addr >= 0x8000) {
            prgBank = (data & 0x0F) % prgBankCount;
            chrBank = ((data >> 4) & 0x0F) % chrBankCount;
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

    // =========================================================================
    // Save state
    // =========================================================================

    @Override
    public MapperState saveState() { return new State(prgBank, chrBank); }

    @Override
    public void loadState(MapperState ms) {
        State s = (State) ms;
        prgBank = s.prgBank; chrBank = s.chrBank;
    }

    private static final class State implements MapperState {
        private static final long serialVersionUID = 1L;
        final int prgBank, chrBank;
        State(int prgBank, int chrBank) { this.prgBank = prgBank; this.chrBank = chrBank; }
    }
}
