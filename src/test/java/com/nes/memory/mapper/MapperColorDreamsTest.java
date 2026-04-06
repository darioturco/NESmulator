package com.nes.memory.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Mapper 11 (Color Dreams).
 */
class MapperColorDreamsTest {

    // 4 PRG banks × 32 KB; each bank filled with its bank index
    private static final int PRG_BANKS = 4;
    private static final byte[] PRG_ROM = makePrgRom(PRG_BANKS);

    // 4 CHR banks × 8 KB; each bank filled with its bank index
    private static final int CHR_BANKS = 4;
    private static final byte[] CHR_ROM = makeChrRom(CHR_BANKS);

    private MapperColorDreams mapper;

    @BeforeEach
    void setUp() {
        mapper = new MapperColorDreams(PRG_ROM, CHR_ROM, false);
    }

    // =========================================================================

    @Nested
    class InitialState {

        @Test
        void prgBank0AtReset() {
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void chrBank0AtReset() {
            assertEquals(0, mapper.ppuRead(0x0000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class PrgBankSwitching {

        @Test
        void selectBank1() {
            mapper.cpuWrite(0x8000, 0x01);  // PRG bits 3-0 = 1
            assertEquals(1, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void selectBank3() {
            mapper.cpuWrite(0x8000, 0x03);
            assertEquals(3, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void writeAnywhereInRomSpace() {
            mapper.cpuWrite(0xFFFF, 0x02);
            assertEquals(2, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void bankSelectWrapsOnOverflow() {
            mapper.cpuWrite(0x8000, PRG_BANKS);   // bank 4 → wraps to 0
            assertEquals(0, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void fullWindowMapped() {
            mapper.cpuWrite(0x8000, 0x02);
            // Both halves of the 32 KB window should read bank 2
            assertEquals(2, mapper.cpuRead(0x8000) & 0xFF);
            assertEquals(2, mapper.cpuRead(0xC000) & 0xFF);
            assertEquals(2, mapper.cpuRead(0xFFFF) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrBankSwitching {

        @Test
        void selectBank1() {
            mapper.cpuWrite(0x8000, 0x10);  // CHR bits 7-4 = 1
            assertEquals(1, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void selectBank3() {
            mapper.cpuWrite(0x8000, 0x30);  // CHR bits 7-4 = 3
            assertEquals(3, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void bankSelectWrapsOnOverflow() {
            mapper.cpuWrite(0x8000, (byte)(CHR_BANKS << 4) & 0xFF);  // bank 4 → wraps to 0
            assertEquals(0, mapper.ppuRead(0x0000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class CombinedBankSwitching {

        @Test
        void prgAndChrSetSimultaneously() {
            mapper.cpuWrite(0x8000, 0x21);  // PRG = 1, CHR = 2
            assertEquals(1, mapper.cpuRead(0x8000) & 0xFF);
            assertEquals(2, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void independentSwitching() {
            mapper.cpuWrite(0x8000, 0x31);  // PRG = 1, CHR = 3
            assertEquals(1, mapper.cpuRead(0x8000) & 0xFF);
            assertEquals(3, mapper.ppuRead(0x0000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrRomReadOnly {

        @Test
        void writeIgnored() {
            int before = mapper.ppuRead(0x0100);
            mapper.ppuWrite(0x0100, before ^ 0xFF);
            assertEquals(before, mapper.ppuRead(0x0100));
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
