package com.nes.memory.mapper;

import com.nes.memory.Cartridge.MirrorMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Mapper 1 (MMC1 / SxROM).
 */
class MapperMMC1Test {

    // 4 PRG banks × 16 KB = 64 KB; each bank filled with its bank number
    private static final int PRG_BANKS = 4;
    private static final byte[] PRG_ROM = makePrgRom(PRG_BANKS);

    // 2 CHR banks × 8 KB = 16 KB; each 4 KB page filled with its page index
    private static final int CHR_BANKS = 2;
    private static final byte[] CHR_ROM = makeChrRom(CHR_BANKS);

    private MapperMMC1 mapper;

    @BeforeEach
    void setUp() {
        mapper = new MapperMMC1(PRG_ROM, CHR_ROM, true);
    }

    // =========================================================================
    // Helper: write a 5-bit value into an MMC1 register via the serial port
    // =========================================================================

    /** Send all 5 bits of {@code value} as consecutive writes to {@code addr}. */
    private void serialWrite(int addr, int value) {
        for (int i = 0; i < 5; i++) {
            mapper.cpuWrite(addr, (value >> i) & 1);
        }
    }

    // =========================================================================

    @Nested
    class InitialState {

        @Test
        void defaultControlIsFixLast() {
            // regControl default = 0x0C → PRG mode 3 (fix-last), CHR 8 KB, horizontal
            // Last bank ($C000) should read from the last 16 KB PRG bank
            int lastBank = PRG_BANKS - 1;
            assertEquals(lastBank, mapper.cpuRead(0xFFFF) & 0xFF);
        }

        @Test
        void defaultMirrorIsSingleScreenLo() {
            // MMC1 power-up: regControl = 0x0C (bits 1-0 = 00 → SINGLE_SCREEN_LO)
            assertEquals(MirrorMode.SINGLE_SCREEN_LO, mapper.getMirrorMode());
        }

        @Test
        void prgRamReadable() {
            mapper.cpuWrite(0x6000, 0xAB);
            assertEquals(0xAB, mapper.cpuRead(0x6000));
        }
    }

    // =========================================================================

    @Nested
    class ShiftRegister {

        @Test
        void resetBitClearsShiftRegister() {
            // Write 4 bits without completing
            mapper.cpuWrite(0x8000, 1);
            mapper.cpuWrite(0x8000, 1);
            mapper.cpuWrite(0x8000, 1);
            mapper.cpuWrite(0x8000, 1);
            // Reset
            mapper.cpuWrite(0x8000, 0x80);
            // Now a full 5-bit write of 0 should set control to 0
            serialWrite(0x8000, 0x00);
            // Mirror mode 0 = single-screen low
            assertEquals(MirrorMode.SINGLE_SCREEN_LO, mapper.getMirrorMode());
        }

        @Test
        void resetAlsoForcesFixLastPrgMode() {
            // Switch to PRG mode 0 first
            serialWrite(0x8000, 0x00);
            // Now reset
            mapper.cpuWrite(0x8000, 0x80);
            // After reset, PRG mode 3 (fix-last) is restored
            int lastBank = PRG_BANKS - 1;
            assertEquals(lastBank, mapper.cpuRead(0xFFFF) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class MirrorModeTests {

        @Test
        void mirrorSingleLo() {
            serialWrite(0x8000, 0b00000); // bits 1-0 = 00
            assertEquals(MirrorMode.SINGLE_SCREEN_LO, mapper.getMirrorMode());
        }

        @Test
        void mirrorSingleHi() {
            serialWrite(0x8000, 0b00001); // bits 1-0 = 01
            assertEquals(MirrorMode.SINGLE_SCREEN_HI, mapper.getMirrorMode());
        }

        @Test
        void mirrorVertical() {
            serialWrite(0x8000, 0b00010); // bits 1-0 = 10
            assertEquals(MirrorMode.VERTICAL, mapper.getMirrorMode());
        }

        @Test
        void mirrorHorizontal() {
            serialWrite(0x8000, 0b00011); // bits 1-0 = 11
            assertEquals(MirrorMode.HORIZONTAL, mapper.getMirrorMode());
        }
    }

    // =========================================================================

    @Nested
    class PrgBankModeTests {

        @Test
        void mode0Switch32KB() {
            // Control: bits 3-2 = 00 → 32 KB mode, horizontal mirror
            serialWrite(0x8000, 0b00011); // mirror=horiz, prgMode=0
            // Select PRG bank pair 2 (i.e., banks 2&3, value=2 ignored low bit)
            serialWrite(0xE000, 2);
            // $8000 should read bank 2, $C000 should read bank 3
            assertEquals(2, mapper.cpuRead(0x8000) & 0xFF);
            assertEquals(3, mapper.cpuRead(0xC000) & 0xFF);
        }

        @Test
        void mode2FixFirstSwitchLast() {
            // Control: bits 3-2 = 10 → fix first at $8000, switch at $C000
            serialWrite(0x8000, 0b01011); // mirror=horiz, prgMode=2
            // Switch $C000 to bank 2
            serialWrite(0xE000, 2);
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);  // fixed = bank 0
            assertEquals(2, mapper.cpuRead(0xC000) & 0xFF);  // switched = bank 2
        }

        @Test
        void mode3FixLastSwitchFirst() {
            // Control: bits 3-2 = 11 → switch at $8000, fix last at $C000 (default)
            serialWrite(0x8000, 0b01111); // mirror=horiz, prgMode=3
            // Switch $8000 to bank 1
            serialWrite(0xE000, 1);
            assertEquals(1, mapper.cpuRead(0x8000) & 0xFF);          // switched = bank 1
            assertEquals(PRG_BANKS - 1, mapper.cpuRead(0xC000) & 0xFF); // fixed = last
        }

        @Test
        void prgBankSelectBeyondBoundsWraps() {
            // With 4 banks, selecting bank 4 should wrap to bank 0
            serialWrite(0x8000, 0b01111); // mode 3
            serialWrite(0xE000, PRG_BANKS); // bank 4 → wraps to 0
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrBankModeTests {

        @Test
        void chrMode8KB() {
            // Control bit 4 = 0 → 8 KB CHR mode
            serialWrite(0x8000, 0b00011); // prgMode=0,mirror=horiz, chr8k
            // CHR bank 0 selects 8 KB chunk; bank 0 = pages 0&1
            serialWrite(0xA000, 0); // select first 8 KB
            assertEquals(0, mapper.ppuRead(0x0000) & 0xFF); // page 0
            assertEquals(1, mapper.ppuRead(0x1000) & 0xFF); // page 1
        }

        @Test
        void chrMode4KB() {
            // Control bit 4 = 1 → 4 KB CHR mode
            serialWrite(0x8000, 0b10011); // prgMode=0,mirror=horiz, chr4k
            serialWrite(0xA000, 2); // $0000 = page 2
            serialWrite(0xC000, 3); // $1000 = page 3
            assertEquals(2, mapper.ppuRead(0x0000) & 0xFF);
            assertEquals(3, mapper.ppuRead(0x1000) & 0xFF);
        }

        @Test
        void chrMode8kbIgnoresChrBank1() {
            // In 8 KB mode, only CHR Bank 0 matters
            serialWrite(0x8000, 0b00011); // chr8k
            serialWrite(0xA000, 0);
            serialWrite(0xC000, 1); // should be ignored in 8 KB mode
            // $1000 still reads from the second half of the 8 KB chunk = page 1
            assertEquals(1, mapper.ppuRead(0x1000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrRamTests {

        @Test
        void chrRamWritableAndReadable() {
            MapperMMC1 ramMapper = new MapperMMC1(PRG_ROM, null, false); // CHR-RAM
            ramMapper.ppuWrite(0x0000, 0xAB);
            assertEquals(0xAB, ramMapper.ppuRead(0x0000));
        }

        @Test
        void chrRomNotWritable() {
            // Writing to CHR-ROM should be silently ignored
            int original = mapper.ppuRead(0x0000);
            mapper.ppuWrite(0x0000, original ^ 0xFF);
            assertEquals(original, mapper.ppuRead(0x0000));
        }
    }

    // =========================================================================

    @Nested
    class PrgRamTests {

        @Test
        void prgRamPersists() {
            mapper.cpuWrite(0x6000, 0x42);
            mapper.cpuWrite(0x6001, 0xFF);
            assertEquals(0x42, mapper.cpuRead(0x6000));
            assertEquals(0xFF, mapper.cpuRead(0x6001));
        }

        @Test
        void prgRamRange() {
            mapper.cpuWrite(0x7FFF, 0x99);
            assertEquals(0x99, mapper.cpuRead(0x7FFF));
        }
    }

    // =========================================================================
    // Factory helpers
    // =========================================================================

    /**
     * Build a PRG-ROM where each 16 KB bank is entirely filled with the bank
     * index value (0, 1, 2, …). This makes it easy to assert which bank is
     * mapped at a given address.
     */
    private static byte[] makePrgRom(int banks) {
        byte[] rom = new byte[banks * 0x4000];
        for (int b = 0; b < banks; b++) {
            for (int i = 0; i < 0x4000; i++) {
                rom[b * 0x4000 + i] = (byte) b;
            }
        }
        return rom;
    }

    /**
     * Build a CHR-ROM where each 4 KB page is entirely filled with the page
     * index value (0, 1, 2, …).
     */
    private static byte[] makeChrRom(int banks8k) {
        int pages = banks8k * 2; // 2 × 4 KB pages per 8 KB bank
        byte[] rom = new byte[pages * 0x1000];
        for (int p = 0; p < pages; p++) {
            for (int i = 0; i < 0x1000; i++) {
                rom[p * 0x1000 + i] = (byte) p;
            }
        }
        return rom;
    }
}
