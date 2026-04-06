package com.nes.memory.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Mapper 3 (CNROM).
 */
class MapperCNROMTest {

    // 32 KB PRG (2 × 16 KB) — NROM-style, fixed
    private static final byte[] PRG_ROM = makePrgRom(2);

    // 4 CHR banks × 8 KB = 32 KB; each bank filled with its bank index
    private static final int CHR_BANKS = 4;
    private static final byte[] CHR_ROM = makeChrRom(CHR_BANKS);

    private MapperCNROM mapper;

    @BeforeEach
    void setUp() {
        mapper = new MapperCNROM(PRG_ROM, CHR_ROM, false);
    }

    // =========================================================================

    @Nested
    class InitialState {

        @Test
        void chrBank0Selected() {
            assertEquals(0, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void prgFixedAt8000() {
            // First PRG bank at $8000
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void prgFixedAtC000() {
            // Second (last) PRG bank at $C000
            assertEquals(1, mapper.cpuRead(0xC000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrBankSwitching {

        @Test
        void selectBank1() {
            mapper.cpuWrite(0x8000, 1);
            assertEquals(1, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void selectBank3() {
            mapper.cpuWrite(0x8000, 3);
            assertEquals(3, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void writeAnywhereInRomSpace() {
            mapper.cpuWrite(0xFFFF, 2);
            assertEquals(2, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void onlyBits10Used() {
            // Upper bits of the write are ignored
            mapper.cpuWrite(0x8000, 0xFC); // bits 1-0 = 00
            assertEquals(0, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void bankSelectWrapsOnOverflow() {
            mapper.cpuWrite(0x8000, CHR_BANKS); // bank 4 → wraps to 0
            assertEquals(0, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void prgUnchangedAfterChrSwitch() {
            mapper.cpuWrite(0x8000, 3);
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);
            assertEquals(1, mapper.cpuRead(0xC000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrRomReadOnly {

        @Test
        void writeIgnored() {
            int before = mapper.ppuRead(0x0000);
            mapper.ppuWrite(0x0000, before ^ 0xFF);
            assertEquals(before, mapper.ppuRead(0x0000));
        }
    }

    // =========================================================================

    private static byte[] makePrgRom(int banks16k) {
        byte[] rom = new byte[banks16k * 0x4000];
        for (int b = 0; b < banks16k; b++) {
            for (int i = 0; i < 0x4000; i++) {
                rom[b * 0x4000 + i] = (byte) b;
            }
        }
        return rom;
    }

    private static byte[] makeChrRom(int banks8k) {
        byte[] rom = new byte[banks8k * 0x2000];
        for (int b = 0; b < banks8k; b++) {
            for (int i = 0; i < 0x2000; i++) {
                rom[b * 0x2000 + i] = (byte) b;
            }
        }
        return rom;
    }
}
