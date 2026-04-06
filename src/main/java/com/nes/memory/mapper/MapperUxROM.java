package com.nes.memory.mapper;

/**
 * Mapper 2 — UxROM (UNROM / UOROM).
 *
 * One of the simplest bank-switching mappers: a single register selects
 * which 16 KB PRG bank is visible at $8000–$BFFF, while the last 16 KB
 * bank is permanently fixed at $C000–$FFFF.
 *
 * CPU memory map:
 *   $8000–$BFFF  Switchable 16 KB PRG bank (selected via register write)
 *   $C000–$FFFF  Fixed last 16 KB PRG bank (always the last bank in ROM)
 *
 * PPU memory map:
 *   $0000–$1FFF  8 KB CHR-RAM (UxROM games never ship with CHR-ROM)
 *
 * Register:
 *   Any write to $8000–$FFFF sets the switchable PRG bank number (bits 3–0).
 *   On UNROM the register is 3 bits wide (8 banks max = 128 KB).
 *   On UOROM the register is 4 bits wide (16 banks max = 256 KB).
 *   We support the full 4-bit range here to handle both variants.
 *
 * Games: Contra, Castlevania, Mega Man, Duck Tales, Metal Gear.
 */
public class MapperUxROM implements Mapper {

    private final byte[] prgRom;
    private final byte[] chrRam;   // always 8 KB CHR-RAM
    private final int    lastBank; // index of the fixed last 16 KB bank

    /** Currently selected 16 KB PRG bank mapped at $8000–$BFFF. */
    private int selectedBank = 0;

    /**
     * @param prgRom full PRG-ROM (N × 16 KB, up to 256 KB)
     * @param chrRom ignored — UxROM always uses CHR-RAM; pass null
     */
    public MapperUxROM(byte[] prgRom, byte[] chrRom, boolean battery) {
        this.prgRom   = prgRom;
        this.chrRam   = new byte[0x2000];
        this.lastBank = (prgRom.length / 0x4000) - 1;
    }

    // =========================================================================
    // CPU side
    // =========================================================================

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x8000 && addr <= 0xBFFF) {
            return prgRom[selectedBank * 0x4000 + (addr & 0x3FFF)] & 0xFF;
        }
        if (addr >= 0xC000) {
            return prgRom[lastBank * 0x4000 + (addr & 0x3FFF)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int data) {
        if (addr >= 0x8000) {
            // Low 4 bits select the bank; mask to valid range
            selectedBank = (data & 0x0F) % (lastBank + 1);
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
    // Save state
    // =========================================================================

    @Override
    public MapperState saveState() {
        return new State(selectedBank, chrRam.clone());
    }

    @Override
    public void loadState(MapperState ms) {
        State s = (State) ms;
        selectedBank = s.selectedBank;
        System.arraycopy(s.chrRam, 0, chrRam, 0, chrRam.length);
    }

    private static final class State implements MapperState {
        private static final long serialVersionUID = 1L;
        final int selectedBank;
        final byte[] chrRam;
        State(int selectedBank, byte[] chrRam) {
            this.selectedBank = selectedBank; this.chrRam = chrRam;
        }
    }
}
