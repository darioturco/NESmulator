package com.nes.memory.mapper;

/**
 * Mapper 0 — NROM.
 *
 * The simplest NES mapper: no bank switching, no registers.
 * All addresses are permanently wired to fixed ROM offsets.
 *
 * CPU memory map:
 *   $6000–$7FFF  PRG-RAM (2–4 KB, present only in Family BASIC)
 *   $8000–$BFFF  PRG-ROM bank 0  (first 16 KB)
 *   $C000–$FFFF  PRG-ROM bank 1  (second 16 KB, or mirror of bank 0 for NROM-128)
 *
 * PPU memory map:
 *   $0000–$1FFF  CHR-ROM (8 KB fixed) or CHR-RAM if no CHR-ROM present
 *
 * Known NROM games: Donkey Kong, Balloon Fight, Ice Climber, Galaga,
 *                   Super Mario Bros. (NROM-256), Excitebike.
 */
public class MapperNROM implements Mapper {

    private final byte[] prgRom;
    private final byte[] chrMem;  // CHR-ROM or CHR-RAM
    private final byte[] prgRam;  // 8 KB PRG-RAM (may be unused)

    /** True when chrMem is writable RAM (chrBanks == 0 in the iNES header). */
    private final boolean hasChrRam;

    /**
     * @param prgRom   full PRG-ROM data (16 KB or 32 KB)
     * @param chrRom   CHR-ROM data, or {@code null} to allocate 8 KB CHR-RAM
     * @param hasPrgRam true if the cartridge has battery-backed PRG-RAM
     */
    public MapperNROM(byte[] prgRom, byte[] chrRom, boolean hasPrgRam) {
        this.prgRom    = prgRom;
        this.hasChrRam = (chrRom == null);
        this.chrMem    = hasChrRam ? new byte[0x2000] : chrRom;
        this.prgRam    = hasPrgRam ? new byte[0x2000] : null; // 8 KB
    }

    // -------------------------------------------------------------------------
    // CPU side
    // -------------------------------------------------------------------------

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x6000 && addr <= 0x7FFF) {
            return prgRam != null ? prgRam[addr & 0x1FFF] & 0xFF : 0;
        }
        if (addr >= 0x8000) {
            // Mirror 16 KB ROM into $8000–$FFFF by masking with ROM size - 1
            return prgRom[addr & (prgRom.length - 1)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int data) {
        if (addr >= 0x6000 && addr <= 0x7FFF && prgRam != null) {
            prgRam[addr & 0x1FFF] = (byte) data;
        }
        // PRG-ROM writes are ignored (no mapper registers on NROM)
    }

    // -------------------------------------------------------------------------
    // PPU side
    // -------------------------------------------------------------------------

    @Override
    public int ppuRead(int addr) {
        return chrMem[addr & 0x1FFF] & 0xFF;
    }

    @Override
    public void ppuWrite(int addr, int data) {
        if (hasChrRam) {
            chrMem[addr & 0x1FFF] = (byte) data;
        }
    }

    // =========================================================================
    // Save state
    // =========================================================================

    @Override
    public MapperState saveState() {
        return new State(
            hasChrRam ? chrMem.clone() : null,
            prgRam != null ? prgRam.clone() : null
        );
    }

    @Override
    public void loadState(MapperState ms) {
        State s = (State) ms;
        if (s.chrMem != null && hasChrRam)
            System.arraycopy(s.chrMem, 0, chrMem, 0, chrMem.length);
        if (s.prgRam != null && prgRam != null)
            System.arraycopy(s.prgRam, 0, prgRam, 0, prgRam.length);
    }

    private static final class State implements MapperState {
        private static final long serialVersionUID = 1L;
        final byte[] chrMem, prgRam;
        State(byte[] chrMem, byte[] prgRam) { this.chrMem = chrMem; this.prgRam = prgRam; }
    }
}
