package com.nes.memory;

import com.nes.memory.mapper.MapperNROM;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Mapper 0 (NROM).
 *
 * Covers PRG-ROM mirroring (16 KB and 32 KB), PRG-RAM, CHR-ROM (read-only),
 * and CHR-RAM (read/write).
 */
class MapperNROMTest {

    // =========================================================================
    // PRG-ROM
    // =========================================================================

    @Nested
    class PrgRom {

        @Test void read_32KB_rom_full_range() {
            byte[] prg = new byte[0x8000]; // 32 KB
            prg[0x0000] = (byte) 0x11;     // maps to $8000
            prg[0x7FFF] = (byte) 0x22;     // maps to $FFFF
            MapperNROM m = new MapperNROM(prg, new byte[0x2000], false);

            assertEquals(0x11, m.cpuRead(0x8000));
            assertEquals(0x22, m.cpuRead(0xFFFF));
        }

        @Test void read_16KB_rom_mirrors_at_C000() {
            byte[] prg = new byte[0x4000]; // 16 KB
            prg[0x0000] = (byte) 0xAA;    // $8000 and $C000
            prg[0x3FFF] = (byte) 0xBB;    // $BFFF and $FFFF
            MapperNROM m = new MapperNROM(prg, new byte[0x2000], false);

            assertEquals(0xAA, m.cpuRead(0x8000));
            assertEquals(0xAA, m.cpuRead(0xC000)); // mirror
            assertEquals(0xBB, m.cpuRead(0xBFFF));
            assertEquals(0xBB, m.cpuRead(0xFFFF)); // mirror
        }

        @Test void write_to_prg_rom_ignored() {
            byte[] prg = new byte[0x4000];
            prg[0] = (byte) 0x55;
            MapperNROM m = new MapperNROM(prg, new byte[0x2000], false);
            m.cpuWrite(0x8000, 0xFF); // should be ignored
            assertEquals(0x55, m.cpuRead(0x8000));
        }

        @Test void read_below_6000_returns_0() {
            MapperNROM m = new MapperNROM(new byte[0x4000], new byte[0x2000], false);
            assertEquals(0, m.cpuRead(0x0000));
            assertEquals(0, m.cpuRead(0x5FFF));
        }
    }

    // =========================================================================
    // PRG-RAM  ($6000–$7FFF)
    // =========================================================================

    @Nested
    class PrgRam {

        @Test void no_prg_ram_reads_zero() {
            MapperNROM m = new MapperNROM(new byte[0x4000], new byte[0x2000], false);
            assertEquals(0, m.cpuRead(0x6000));
        }

        @Test void no_prg_ram_writes_ignored() {
            MapperNROM m = new MapperNROM(new byte[0x4000], new byte[0x2000], false);
            m.cpuWrite(0x6000, 0x42); // should not throw
            assertEquals(0, m.cpuRead(0x6000));
        }

        @Test void prg_ram_read_write() {
            MapperNROM m = new MapperNROM(new byte[0x4000], new byte[0x2000], true);
            m.cpuWrite(0x6000, 0xDE);
            assertEquals(0xDE, m.cpuRead(0x6000));
        }

        @Test void prg_ram_8KB_range() {
            MapperNROM m = new MapperNROM(new byte[0x4000], new byte[0x2000], true);
            m.cpuWrite(0x7FFF, 0x99);
            assertEquals(0x99, m.cpuRead(0x7FFF));
        }

        @Test void prg_ram_address_masked_to_8KB() {
            MapperNROM m = new MapperNROM(new byte[0x4000], new byte[0x2000], true);
            m.cpuWrite(0x6000, 0x77);
            // 0x6000 & 0x1FFF == 0x0000; same offset
            assertEquals(0x77, m.cpuRead(0x6000));
        }
    }

    // =========================================================================
    // CHR-ROM (read-only)
    // =========================================================================

    @Nested
    class ChrRom {

        @Test void read_chr_rom() {
            byte[] chr = new byte[0x2000];
            chr[0x0100] = (byte) 0xEF;
            MapperNROM m = new MapperNROM(new byte[0x4000], chr, false);
            assertEquals(0xEF, m.ppuRead(0x0100));
        }

        @Test void write_to_chr_rom_ignored() {
            byte[] chr = new byte[0x2000];
            chr[0x0000] = (byte) 0x33;
            MapperNROM m = new MapperNROM(new byte[0x4000], chr, false);
            m.ppuWrite(0x0000, 0xFF); // must be ignored
            assertEquals(0x33, m.ppuRead(0x0000));
        }

        @Test void chr_address_masked_to_8KB() {
            byte[] chr = new byte[0x2000];
            chr[0x1000] = (byte) 0x5A;
            MapperNROM m = new MapperNROM(new byte[0x4000], chr, false);
            assertEquals(0x5A, m.ppuRead(0x1000));
        }
    }

    // =========================================================================
    // CHR-RAM (null chrRom → allocate 8KB writable RAM)
    // =========================================================================

    @Nested
    class ChrRam {

        @Test void chr_ram_read_write() {
            MapperNROM m = new MapperNROM(new byte[0x4000], null, false);
            m.ppuWrite(0x0050, 0xAB);
            assertEquals(0xAB, m.ppuRead(0x0050));
        }

        @Test void chr_ram_initially_zero() {
            MapperNROM m = new MapperNROM(new byte[0x4000], null, false);
            assertEquals(0, m.ppuRead(0x0000));
            assertEquals(0, m.ppuRead(0x1FFF));
        }

        @Test void chr_ram_full_8KB_range() {
            MapperNROM m = new MapperNROM(new byte[0x4000], null, false);
            m.ppuWrite(0x1FFF, 0xCC);
            assertEquals(0xCC, m.ppuRead(0x1FFF));
        }
    }
}
