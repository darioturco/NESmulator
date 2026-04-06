package com.nes.memory.mapper;

import com.nes.memory.Cartridge.MirrorMode;

/**
 * Mapper 9 — MMC2 (PxROM).
 *
 * Used exclusively by Mike Tyson's Punch-Out!! and Punch-Out!!
 *
 * The defining feature of MMC2 is its CHR latch mechanism: two latches
 * (one per 4 KB CHR window) automatically switch the active CHR bank when
 * the PPU reads a specific tile address. This was engineered to let Punch-Out!!
 * display large, detailed boxer faces that span the CHR bank boundary.
 *
 * CPU memory map:
 *   $8000–$9FFF  Switchable 8 KB PRG bank (register at $A000–$AFFF)
 *   $A000–$BFFF  Fixed: third-to-last 8 KB PRG bank
 *   $C000–$DFFF  Fixed: second-to-last 8 KB PRG bank
 *   $E000–$FFFF  Fixed: last 8 KB PRG bank
 *
 * PPU memory map:
 *   $0000–$0FFF  4 KB CHR bank selected by latch0 (R0FD or R0FE)
 *   $1000–$1FFF  4 KB CHR bank selected by latch1 (R1FD or R1FE)
 *
 * Registers (written to CPU ROM space — ignored as ROM writes, decoded by addr):
 *   $A000–$AFFF  PRG bank select (bits 3–0)
 *   $B000–$BFFF  CHR bank for lower window when latch0 = $FD  (R0FD)
 *   $C000–$CFFF  CHR bank for lower window when latch0 = $FE  (R0FE)
 *   $D000–$DFFF  CHR bank for upper window when latch1 = $FD  (R1FD)
 *   $E000–$EFFF  CHR bank for upper window when latch1 = $FE  (R1FE)
 *   $F000–$FFFF  Mirroring: bit 0 = 0 → vertical, 1 → horizontal
 *
 * CHR latch trigger addresses (side-effect of ppuRead):
 *   Read $0FD8        → latch0 = $FD  (lower window switches to R0FD)
 *   Read $0FE8        → latch0 = $FE  (lower window switches to R0FE)
 *   Read $1FD8–$1FDF  → latch1 = $FD  (upper window switches to R1FD)
 *   Read $1FE8–$1FEF  → latch1 = $FE  (upper window switches to R1FE)
 */
public class MapperMMC2 implements Mapper {

    private final byte[] prgRom;
    private final byte[] chrRom;
    private final int    prgBank8Count;
    private final int    chrBank4Count; // number of 4 KB CHR pages

    // PRG bank register
    private int prgBank = 0;

    // CHR bank registers (4 of them — two per latch state)
    private int r0fd = 0, r0fe = 1; // lower window: latch0=FD → r0fd, latch0=FE → r0fe
    private int r1fd = 0, r1fe = 1; // upper window: latch1=FD → r1fd, latch1=FE → r1fe

    // Latches (initial value = $FE so games see r0fe/r1fe until the latch trips)
    private boolean latch0fe = true; // true → use r0fe; false → use r0fd
    private boolean latch1fe = true; // true → use r1fe; false → use r1fd

    private MirrorMode mirrorMode = MirrorMode.VERTICAL;

    public MapperMMC2(byte[] prgRom, byte[] chrRom, boolean battery) {
        this.prgRom        = prgRom;
        this.chrRom        = (chrRom != null) ? chrRom : new byte[0x2000];
        this.prgBank8Count = prgRom.length / 0x2000;
        this.chrBank4Count = Math.max(1, this.chrRom.length / 0x1000);
    }

    // =========================================================================
    // CPU side
    // =========================================================================

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x8000 && addr <= 0x9FFF) {
            return prgRom[(prgBank % prgBank8Count) * 0x2000 + (addr & 0x1FFF)] & 0xFF;
        }
        if (addr >= 0xA000 && addr <= 0xBFFF) {
            return prgRom[(prgBank8Count - 3) * 0x2000 + (addr & 0x1FFF)] & 0xFF;
        }
        if (addr >= 0xC000 && addr <= 0xDFFF) {
            return prgRom[(prgBank8Count - 2) * 0x2000 + (addr & 0x1FFF)] & 0xFF;
        }
        if (addr >= 0xE000) {
            return prgRom[(prgBank8Count - 1) * 0x2000 + (addr & 0x1FFF)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int data) {
        if      (addr >= 0xA000 && addr <= 0xAFFF) prgBank    = data & 0x0F;
        else if (addr >= 0xB000 && addr <= 0xBFFF) r0fd       = data & 0x1F;
        else if (addr >= 0xC000 && addr <= 0xCFFF) r0fe       = data & 0x1F;
        else if (addr >= 0xD000 && addr <= 0xDFFF) r1fd       = data & 0x1F;
        else if (addr >= 0xE000 && addr <= 0xEFFF) r1fe       = data & 0x1F;
        else if (addr >= 0xF000)
            mirrorMode = (data & 1) != 0 ? MirrorMode.HORIZONTAL : MirrorMode.VERTICAL;
    }

    // =========================================================================
    // PPU side  (latch side-effects happen here)
    // =========================================================================

    @Override
    public int ppuRead(int addr) {
        int value;
        if (addr < 0x1000) {
            int bank = latch0fe ? r0fe : r0fd;
            value = chrRom[(bank % chrBank4Count) * 0x1000 + (addr & 0x0FFF)] & 0xFF;
            // Latch trigger: $0FD8 → latch0 = FD; $0FE8 → latch0 = FE
            if (addr == 0x0FD8) latch0fe = false;
            else if (addr == 0x0FE8) latch0fe = true;
        } else {
            int bank = latch1fe ? r1fe : r1fd;
            value = chrRom[(bank % chrBank4Count) * 0x1000 + (addr & 0x0FFF)] & 0xFF;
            // Latch trigger: $1FD8–$1FDF → latch1 = FD; $1FE8–$1FEF → latch1 = FE
            if (addr >= 0x1FD8 && addr <= 0x1FDF) latch1fe = false;
            else if (addr >= 0x1FE8 && addr <= 0x1FEF) latch1fe = true;
        }
        return value;
    }

    @Override
    public void ppuWrite(int addr, int data) {
        // CHR-ROM is read-only; MMC2 games never use CHR-RAM
    }

    // =========================================================================
    // Mirror mode
    // =========================================================================

    public MirrorMode getMirrorMode() {
        return mirrorMode;
    }

    // =========================================================================
    // Save state
    // =========================================================================

    @Override
    public MapperState saveState() {
        return new State(prgBank, r0fd, r0fe, r1fd, r1fe,
                         latch0fe, latch1fe, mirrorMode);
    }

    @Override
    public void loadState(MapperState ms) {
        State s = (State) ms;
        prgBank = s.prgBank;
        r0fd = s.r0fd; r0fe = s.r0fe; r1fd = s.r1fd; r1fe = s.r1fe;
        latch0fe = s.latch0fe; latch1fe = s.latch1fe;
        mirrorMode = s.mirrorMode;
    }

    private static final class State implements MapperState {
        private static final long serialVersionUID = 1L;
        final int prgBank, r0fd, r0fe, r1fd, r1fe;
        final boolean latch0fe, latch1fe;
        final MirrorMode mirrorMode;
        State(int prgBank, int r0fd, int r0fe, int r1fd, int r1fe,
              boolean latch0fe, boolean latch1fe, MirrorMode mirrorMode) {
            this.prgBank = prgBank; this.r0fd = r0fd; this.r0fe = r0fe;
            this.r1fd = r1fd; this.r1fe = r1fe;
            this.latch0fe = latch0fe; this.latch1fe = latch1fe; this.mirrorMode = mirrorMode;
        }
    }
}
