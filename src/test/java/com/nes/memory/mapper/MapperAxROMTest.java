package com.nes.memory.mapper;

import com.nes.memory.Cartridge.MirrorMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Mapper 7 (AxROM).
 */
class MapperAxROMTest {

    // 4 × 32 KB PRG banks = 128 KB; each bank filled with its index
    private static final int PRG_BANK_COUNT = 4;
    private static final byte[] PRG_ROM = makePrgRom(PRG_BANK_COUNT);

    private MapperAxROM mapper;

    @BeforeEach
    void setUp() {
        mapper = new MapperAxROM(PRG_ROM, null, false);
    }

    // =========================================================================

    @Nested
    class InitialState {

        @Test
        void bank0SelectedByDefault() {
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);
            assertEquals(0, mapper.cpuRead(0xFFFF) & 0xFF);
        }

        @Test
        void defaultMirrorIsSingleScreenLo() {
            assertEquals(MirrorMode.SINGLE_SCREEN_LO, mapper.getMirrorMode());
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
        void selectBank1() {
            mapper.cpuWrite(0x8000, 1);
            assertEquals(1, mapper.cpuRead(0x8000) & 0xFF);
            assertEquals(1, mapper.cpuRead(0xFFFF) & 0xFF); // entire 32 KB window
        }

        @Test
        void selectLastBank() {
            mapper.cpuWrite(0x8000, PRG_BANK_COUNT - 1);
            assertEquals(PRG_BANK_COUNT - 1, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void writeAnywhereInRomSpaceUpdatesBank() {
            mapper.cpuWrite(0xFFFF, 2);
            assertEquals(2, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void bankSelectWrapsOnOverflow() {
            mapper.cpuWrite(0x8000, PRG_BANK_COUNT); // 4 → wraps to 0
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void onlyBits20UsedForBank() {
            // Bits 7-3 and 5-3 are ignored; only bits 2-0 select bank
            mapper.cpuWrite(0x8000, 0xE9); // bits 2-0 = 001 → bank 1
            assertEquals(1, mapper.cpuRead(0x8000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class MirrorModeTests {

        @Test
        void bit4HighSelectsSingleScreenHi() {
            mapper.cpuWrite(0x8000, 0x10);
            assertEquals(MirrorMode.SINGLE_SCREEN_HI, mapper.getMirrorMode());
        }

        @Test
        void bit4LowSelectsSingleScreenLo() {
            mapper.cpuWrite(0x8000, 0x10); // set hi first
            mapper.cpuWrite(0x8000, 0x00); // back to lo
            assertEquals(MirrorMode.SINGLE_SCREEN_LO, mapper.getMirrorMode());
        }

        @Test
        void mirrorAndBankChangeTogether() {
            mapper.cpuWrite(0x8000, 0x12); // bank 2, SINGLE_SCREEN_HI
            assertEquals(2, mapper.cpuRead(0x8000) & 0xFF);
            assertEquals(MirrorMode.SINGLE_SCREEN_HI, mapper.getMirrorMode());
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
            mapper.ppuWrite(0x1FFF, 0x55);
            assertEquals(0x55, mapper.ppuRead(0x1FFF));
        }

        @Test
        void independentFromPrg() {
            mapper.cpuWrite(0x8000, 3);
            mapper.ppuWrite(0x0200, 0x77);
            assertEquals(0x77, mapper.ppuRead(0x0200));
            assertEquals(3, mapper.cpuRead(0x8000) & 0xFF);
        }
    }

    // =========================================================================

    private static byte[] makePrgRom(int banks32k) {
        byte[] rom = new byte[banks32k * 0x8000];
        for (int b = 0; b < banks32k; b++) {
            for (int i = 0; i < 0x8000; i++) {
                rom[b * 0x8000 + i] = (byte) b;
            }
        }
        return rom;
    }
}
