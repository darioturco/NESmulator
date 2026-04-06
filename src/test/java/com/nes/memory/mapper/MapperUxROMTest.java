package com.nes.memory.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Mapper 2 (UxROM).
 */
class MapperUxROMTest {

    // 8 PRG banks × 16 KB = 128 KB; each bank filled with its bank number
    private static final int PRG_BANKS = 8;
    private static final byte[] PRG_ROM = makePrgRom(PRG_BANKS);

    private MapperUxROM mapper;

    @BeforeEach
    void setUp() {
        mapper = new MapperUxROM(PRG_ROM, null, false);
    }

    // =========================================================================

    @Nested
    class InitialState {

        @Test
        void bank0MappedAtLow() {
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void lastBankFixedAtHigh() {
            assertEquals(PRG_BANKS - 1, mapper.cpuRead(0xC000) & 0xFF);
        }

        @Test
        void chrRamZeroInitialised() {
            assertEquals(0, mapper.ppuRead(0x0000));
        }
    }

    // =========================================================================

    @Nested
    class BankSwitching {

        @Test
        void selectBank3() {
            mapper.cpuWrite(0x8000, 3);
            assertEquals(3, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void selectLastBankExplicitly() {
            mapper.cpuWrite(0x8000, PRG_BANKS - 1);
            assertEquals(PRG_BANKS - 1, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void highWindowAlwaysLastBank() {
            mapper.cpuWrite(0x8000, 2);
            assertEquals(PRG_BANKS - 1, mapper.cpuRead(0xFFFF) & 0xFF);
        }

        @Test
        void writeAnywhereIn8000ToCFFF() {
            // Register is mirrored across $8000-$FFFF
            mapper.cpuWrite(0xC000, 5);
            assertEquals(5, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void bankSelectWrapsOnOverflow() {
            // Selecting bank 8 on an 8-bank ROM wraps to bank 0
            mapper.cpuWrite(0x8000, PRG_BANKS);
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void onlyLow4BitsUsed() {
            // Upper bits should be ignored
            mapper.cpuWrite(0x8000, 0xF3); // bits 3-0 = 3
            assertEquals(3, mapper.cpuRead(0x8000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrRam {

        @Test
        void writeAndRead() {
            mapper.ppuWrite(0x0000, 0xAB);
            assertEquals(0xAB, mapper.ppuRead(0x0000));
        }

        @Test
        void fullRange() {
            mapper.ppuWrite(0x1FFF, 0x77);
            assertEquals(0x77, mapper.ppuRead(0x1FFF));
        }

        @Test
        void independentFromPrg() {
            mapper.cpuWrite(0x8000, 3);
            mapper.ppuWrite(0x0100, 0x55);
            assertEquals(0x55, mapper.ppuRead(0x0100));
            assertEquals(3, mapper.cpuRead(0x8000) & 0xFF);
        }
    }

    // =========================================================================

    private static byte[] makePrgRom(int banks) {
        byte[] rom = new byte[banks * 0x4000];
        for (int b = 0; b < banks; b++) {
            for (int i = 0; i < 0x4000; i++) {
                rom[b * 0x4000 + i] = (byte) b;
            }
        }
        return rom;
    }
}
