package com.nes.memory.mapper;

import com.nes.memory.Cartridge.MirrorMode;

/**
 * Mapper 7 — AxROM (AMROM / ANROM / AOROM / APROM).
 *
 * Simple 32 KB PRG bank-switching mapper. A single register selects
 * which 32 KB PRG bank fills the entire $8000–$FFFF window, and one
 * bit controls single-screen nametable mirroring.
 *
 * CPU memory map:
 *   $8000–$FFFF  Switchable 32 KB PRG bank (bits 2–0 of register)
 *
 * PPU memory map:
 *   $0000–$1FFF  8 KB CHR-RAM (AxROM games never ship with CHR-ROM)
 *
 * Register (write to $8000–$FFFF):
 *   bits 2–0  PRG bank number (up to 8 banks = 256 KB)
 *   bit  4    Nametable page: 0 = SINGLE_SCREEN_LO, 1 = SINGLE_SCREEN_HI
 *
 * Games: Battletoads, Wizards & Warriors, Cobra Triangle, RC Pro-Am,
 *        Marble Madness, Ironsword.
 */
public class MapperAxROM implements Mapper {

    private final byte[] prgRom;
    private final byte[] chrRam = new byte[0x2000];  // 8 KB CHR-RAM
    private final int    prgBankCount;                // number of 32 KB banks

    private int        selectedBank = 0;
    private MirrorMode mirrorMode   = MirrorMode.SINGLE_SCREEN_LO;

    public MapperAxROM(byte[] prgRom, byte[] chrRom, boolean battery) {
        this.prgRom       = prgRom;
        this.prgBankCount = Math.max(1, prgRom.length / 0x8000);
    }

    // =========================================================================
    // CPU side
    // =========================================================================

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x8000) {
            return prgRom[selectedBank * 0x8000 + (addr & 0x7FFF)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int data) {
        if (addr >= 0x8000) {
            selectedBank = (data & 0x07) % prgBankCount;
            mirrorMode   = (data & 0x10) != 0
                    ? MirrorMode.SINGLE_SCREEN_HI
                    : MirrorMode.SINGLE_SCREEN_LO;
        }
    }

    // =========================================================================
    // PPU side
    // =========================================================================

    @Override
    public int ppuRead(int addr) {
        return chrRam[addr & 0x1FFF] & 0xFF;
    }

    @Override
    public void ppuWrite(int addr, int data) {
        chrRam[addr & 0x1FFF] = (byte) data;
    }

    // =========================================================================
    // Mirror mode (dynamic — readable by Cartridge)
    // =========================================================================

    public MirrorMode getMirrorMode() {
        return mirrorMode;
    }

    // =========================================================================
    // Save state
    // =========================================================================

    @Override
    public MapperState saveState() {
        return new State(selectedBank, mirrorMode, chrRam.clone());
    }

    @Override
    public void loadState(MapperState ms) {
        State s = (State) ms;
        selectedBank = s.selectedBank;
        mirrorMode   = s.mirrorMode;
        System.arraycopy(s.chrRam, 0, chrRam, 0, chrRam.length);
    }

    private static final class State implements MapperState {
        private static final long serialVersionUID = 1L;
        final int selectedBank;
        final MirrorMode mirrorMode;
        final byte[] chrRam;
        State(int selectedBank, MirrorMode mirrorMode, byte[] chrRam) {
            this.selectedBank = selectedBank; this.mirrorMode = mirrorMode; this.chrRam = chrRam;
        }
    }
}
