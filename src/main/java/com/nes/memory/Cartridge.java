package com.nes.memory;

import com.nes.memory.mapper.Mapper;
import com.nes.memory.mapper.MapperAxROM;
import com.nes.memory.mapper.MapperCNROM;
import com.nes.memory.mapper.MapperMMC1;
import com.nes.memory.mapper.MapperMMC2;
import com.nes.memory.mapper.MapperMMC3;
import com.nes.memory.mapper.MapperNROM;
import com.nes.memory.mapper.MapperUxROM;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * NES cartridge — loads an iNES (.nes) file and exposes PRG and CHR memory
 * through the appropriate mapper.
 *
 * iNES header layout (16 bytes):
 *   Bytes 0-3  "NES\x1A" magic
 *   Byte  4    PRG-ROM size in 16 KB units
 *   Byte  5    CHR-ROM size in  8 KB units (0 = uses CHR-RAM)
 *   Byte  6    Flags: mirroring (bit 0), battery (bit 1), trainer (bit 2),
 *                     four-screen (bit 3), mapper low nibble (bits 7-4)
 *   Byte  7    Flags: mapper high nibble (bits 7-4)
 *   Bytes 8-15 Mostly unused in iNES 1.0
 */
public class Cartridge {

    private Mapper mapper;
    private MirrorMode mirrorMode;
    private int mapperId;

    public enum MirrorMode {
        HORIZONTAL, VERTICAL, SINGLE_SCREEN_LO, SINGLE_SCREEN_HI, FOUR_SCREEN
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    /**
     * Parse an iNES ROM file and initialise the mapper.
     *
     * @param path path to the .nes file
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the header is invalid or mapper unsupported
     */
    public void load(String path) throws IOException {
        byte[] raw = Files.readAllBytes(Paths.get(path));

        // Validate magic
        if (raw.length < 16 || raw[0] != 'N' || raw[1] != 'E'
                             || raw[2] != 'S' || raw[3] != 0x1A) {
            throw new IllegalArgumentException("Not a valid iNES file: " + path);
        }

        int prgBanks = raw[4] & 0xFF;          // number of 16 KB PRG banks
        int chrBanks = raw[5] & 0xFF;          // number of  8 KB CHR banks (0 = CHR-RAM)
        int flags6   = raw[6] & 0xFF;
        int flags7   = raw[7] & 0xFF;

        mapperId  = (flags7 & 0xF0) | (flags6 >> 4);
        boolean battery    = (flags6 & 0x02) != 0;
        boolean hasTrainer = (flags6 & 0x04) != 0;
        boolean fourScreen = (flags6 & 0x08) != 0;

        if (fourScreen) {
            mirrorMode = MirrorMode.FOUR_SCREEN;
        } else if ((flags6 & 0x01) != 0) {
            mirrorMode = MirrorMode.VERTICAL;
        } else {
            mirrorMode = MirrorMode.HORIZONTAL;
        }

        // Offset past header (and optional 512-byte trainer)
        int offset = 16 + (hasTrainer ? 512 : 0);

        // Read PRG-ROM
        int prgSize = prgBanks * 16384;
        byte[] prgRom = Arrays.copyOfRange(raw, offset, offset + prgSize);
        offset += prgSize;

        // Read CHR-ROM (may be absent → CHR-RAM)
        byte[] chrRom = null;
        if (chrBanks > 0) {
            int chrSize = chrBanks * 8192;
            chrRom = Arrays.copyOfRange(raw, offset, offset + chrSize);
        }

        // Instantiate mapper
        mapper = createMapper(mapperId, prgRom, chrRom, battery);

        System.out.printf("[Cartridge] Loaded \"%s\"%n"
                        + "  Mapper %d | PRG %d KB | CHR %s | Mirror %s%n",
                Paths.get(path).getFileName(),
                mapperId,
                prgBanks * 16,
                chrBanks > 0 ? chrBanks * 8 + " KB ROM" : "RAM",
                mirrorMode);
    }

    private static Mapper createMapper(int id, byte[] prgRom, byte[] chrRom, boolean battery) {
        switch (id) {
            case 0: return new MapperNROM(prgRom, chrRom, battery);
            case 1: return new MapperMMC1(prgRom, chrRom, battery);
            case 2: return new MapperUxROM(prgRom, chrRom, battery);
            case 3: return new MapperCNROM(prgRom, chrRom, battery);
            case 4: return new MapperMMC3(prgRom, chrRom, battery);
            case 7: return new MapperAxROM(prgRom, chrRom, battery);
            case 9: return new MapperMMC2(prgRom, chrRom, battery);
            default:
                throw new IllegalArgumentException("Unsupported mapper: " + id);
        }
    }

    // -------------------------------------------------------------------------
    // CPU-side access
    // -------------------------------------------------------------------------

    public int cpuRead(int addr) {
        return mapper != null ? mapper.cpuRead(addr) : 0;
    }

    public void cpuWrite(int addr, int data) {
        if (mapper != null) mapper.cpuWrite(addr, data);
    }

    // -------------------------------------------------------------------------
    // PPU-side access
    // -------------------------------------------------------------------------

    public int ppuRead(int addr) {
        return mapper != null ? mapper.ppuRead(addr) : 0;
    }

    public void ppuWrite(int addr, int data) {
        if (mapper != null) mapper.ppuWrite(addr, data);
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public MirrorMode getMirrorMode() {
        // Mappers with dynamic mirroring override the iNES header value
        if (mapper instanceof MapperMMC1) return ((MapperMMC1) mapper).getMirrorMode();
        if (mapper instanceof MapperMMC2) return ((MapperMMC2) mapper).getMirrorMode();
        if (mapper instanceof MapperMMC3) return ((MapperMMC3) mapper).getMirrorMode();
        if (mapper instanceof MapperAxROM) return ((MapperAxROM) mapper).getMirrorMode();
        return mirrorMode;
    }

    /** Forward to the mapper's scanline IRQ counter (MMC3 and similar). */
    public void tickScanline() {
        if (mapper != null) mapper.tickScanline();
    }

    /** True when the mapper has raised an IRQ the CPU must service. */
    public boolean irqPending() {
        return mapper != null && mapper.irqPending();
    }

    public int getMapperId() { return mapperId; }
}
