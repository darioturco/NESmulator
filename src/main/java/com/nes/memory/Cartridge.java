package com.nes.memory;

import java.io.IOException;

/**
 * NES cartridge — loads an iNES (.nes) file and exposes PRG and CHR memory.
 *
 * iNES header (16 bytes):
 *   Bytes 0-3:  "NES\x1A" magic
 *   Byte  4:    PRG-ROM size in 16KB units
 *   Byte  5:    CHR-ROM size in 8KB units (0 = uses CHR-RAM)
 *   Byte  6:    Flags 6 (lower mapper nibble, mirroring, battery, trainer)
 *   Byte  7:    Flags 7 (upper mapper nibble, NES 2.0 marker)
 *   Bytes 8-15: Mostly unused / NES 2.0 extensions
 */
public class Cartridge {

    private byte[] prgRom;  // Program ROM (CPU $8000-$FFFF)
    private byte[] chrRom;  // Character ROM/RAM (PPU $0000-$1FFF)
    private byte[] prgRam;  // Battery-backed RAM (CPU $6000-$7FFF), 8KB

    private int mapperId;
    private int prgBanks;   // Number of 16KB PRG banks
    private int chrBanks;   // Number of 8KB CHR banks

    // Nametable mirroring mode
    private MirrorMode mirrorMode;

    public enum MirrorMode {
        HORIZONTAL, VERTICAL, SINGLE_SCREEN_LO, SINGLE_SCREEN_HI, FOUR_SCREEN
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    /**
     * Load and parse an iNES ROM file.
     *
     * @param path absolute or relative path to the .nes file
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if the header is invalid or the mapper is unsupported
     */
    public void load(String path) throws IOException {
    }

    // -------------------------------------------------------------------------
    // CPU-side access (PRG ROM/RAM)
    // -------------------------------------------------------------------------

    /**
     * CPU read from cartridge space ($4020-$FFFF).
     * Returns value in [0, 255], or 0 if the address is unmapped.
     */
    public int cpuRead(int addr) {
        return 0;
    }

    /**
     * CPU write to cartridge space (used for mapper registers and PRG-RAM).
     */
    public void cpuWrite(int addr, int data) {
    }

    // -------------------------------------------------------------------------
    // PPU-side access (CHR ROM/RAM)
    // -------------------------------------------------------------------------

    /**
     * PPU read from pattern/nametable space ($0000-$1FFF).
     * Returns value in [0, 255].
     */
    public int ppuRead(int addr) {
        return 0;
    }

    /**
     * PPU write to CHR space (only effective when using CHR-RAM).
     */
    public void ppuWrite(int addr, int data) {
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------
 
    public MirrorMode getMirrorMode() {
        return mirrorMode;
    }

    public int getMapperId() {
        return mapperId;
    }
}
