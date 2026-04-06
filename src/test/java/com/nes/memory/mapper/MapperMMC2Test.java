package com.nes.memory.mapper;

import com.nes.memory.Cartridge.MirrorMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Mapper 9 (MMC2 / PxROM).
 *
 * The critical behaviour to verify is the CHR latch: reading certain PPU
 * addresses ($0FD8, $0FE8, $1FD8–$1FDF, $1FE8–$1FEF) switches the active
 * CHR bank for the lower or upper 4 KB window.
 */
class MapperMMC2Test {

    // 16 × 8 KB PRG banks = 128 KB (matches Punch-Out!!)
    // Each bank is filled with its index value so reads are easy to assert.
    private static final int PRG_BANK8_COUNT = 16;
    private static final byte[] PRG_ROM = makePrgRom(PRG_BANK8_COUNT);

    // 4 × 4 KB CHR pages = 16 KB; each page filled with its index
    private static final int CHR_PAGE_COUNT = 4;
    private static final byte[] CHR_ROM = makeChrRom(CHR_PAGE_COUNT);

    private MapperMMC2 mapper;

    @BeforeEach
    void setUp() {
        mapper = new MapperMMC2(PRG_ROM, CHR_ROM, false);
        // Set CHR registers to distinct values so we can tell them apart
        // R0FD=0, R0FE=1, R1FD=2, R1FE=3
        mapper.cpuWrite(0xB000, 0); // r0fd = page 0
        mapper.cpuWrite(0xC000, 1); // r0fe = page 1
        mapper.cpuWrite(0xD000, 2); // r1fd = page 2
        mapper.cpuWrite(0xE000, 3); // r1fe = page 3
    }

    // =========================================================================

    @Nested
    class InitialState {

        @Test
        void defaultLatch0IsFe() {
            // Initial latch0 = FE → lower window uses r0fe (page 1)
            assertEquals(1, mapper.ppuRead(0x0000) & 0xFF);
        }

        @Test
        void defaultLatch1IsFe() {
            // Initial latch1 = FE → upper window uses r1fe (page 3)
            assertEquals(3, mapper.ppuRead(0x1000) & 0xFF);
        }

        @Test
        void defaultMirrorIsVertical() {
            assertEquals(MirrorMode.VERTICAL, mapper.getMirrorMode());
        }
    }

    // =========================================================================

    @Nested
    class PrgBanking {

        @Test
        void switchableBankAt8000() {
            mapper.cpuWrite(0xA000, 5);
            assertEquals(5, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void fixedThirdToLastAtA000() {
            assertEquals(PRG_BANK8_COUNT - 3, mapper.cpuRead(0xA000) & 0xFF);
        }

        @Test
        void fixedSecondToLastAtC000() {
            assertEquals(PRG_BANK8_COUNT - 2, mapper.cpuRead(0xC000) & 0xFF);
        }

        @Test
        void fixedLastAtE000() {
            assertEquals(PRG_BANK8_COUNT - 1, mapper.cpuRead(0xE000) & 0xFF);
        }

        @Test
        void fixedBanksUnchangedAfterPrgSwitch() {
            mapper.cpuWrite(0xA000, 7);
            assertEquals(PRG_BANK8_COUNT - 3, mapper.cpuRead(0xA000) & 0xFF);
            assertEquals(PRG_BANK8_COUNT - 2, mapper.cpuRead(0xC000) & 0xFF);
            assertEquals(PRG_BANK8_COUNT - 1, mapper.cpuRead(0xE000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrLatch0 {

        @Test
        void reading0xFD8SwitchesLatchToFd() {
            // latch0 starts at FE (page 1); reading $0FD8 flips it to FD
            mapper.ppuRead(0x0FD8);
            assertEquals(0, mapper.ppuRead(0x0000) & 0xFF); // now using r0fd = page 0
        }

        @Test
        void reading0xFE8SwitchesLatchToFe() {
            mapper.ppuRead(0x0FD8); // flip to FD first
            mapper.ppuRead(0x0FE8); // flip back to FE
            assertEquals(1, mapper.ppuRead(0x0000) & 0xFF); // back to r0fe = page 1
        }

        @Test
        void latchSwitchReturnsByteAtTriggerAddress() {
            // The read that triggers the latch should still return the value
            // from the bank that was selected BEFORE the switch.
            // $0FD8 is read while latch0=FE → returns from r0fe (page 1)
            int val = mapper.ppuRead(0x0FD8) & 0xFF;
            assertEquals(1, val); // page 1, byte at offset $FD8
        }

        @Test
        void latch0DoesNotAffectUpperWindow() {
            mapper.ppuRead(0x0FD8); // flip latch0
            // upper window still uses latch1 = FE → page 3
            assertEquals(3, mapper.ppuRead(0x1000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrLatch1 {

        @Test
        void reading1xFD8SwitchesLatch1ToFd() {
            mapper.ppuRead(0x1FD8);
            assertEquals(2, mapper.ppuRead(0x1000) & 0xFF); // r1fd = page 2
        }

        @Test
        void reading1xFE8SwitchesLatch1ToFe() {
            mapper.ppuRead(0x1FD8); // flip to FD
            mapper.ppuRead(0x1FE8); // flip back to FE
            assertEquals(3, mapper.ppuRead(0x1000) & 0xFF); // r1fe = page 3
        }

        @Test
        void latch1TriggerRangeEnd1FDF() {
            mapper.ppuRead(0x1FDF); // last byte of the FD trigger range
            assertEquals(2, mapper.ppuRead(0x1000) & 0xFF);
        }

        @Test
        void latch1TriggerRangeEnd1FEF() {
            mapper.ppuRead(0x1FD8); // set to FD first
            mapper.ppuRead(0x1FEF); // last byte of the FE trigger range
            assertEquals(3, mapper.ppuRead(0x1000) & 0xFF);
        }

        @Test
        void latch1DoesNotAffectLowerWindow() {
            mapper.ppuRead(0x1FD8); // flip latch1
            // lower window still uses latch0 = FE → page 1
            assertEquals(1, mapper.ppuRead(0x0000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class MirrorModeTests {

        @Test
        void horizontalMirror() {
            mapper.cpuWrite(0xF000, 1);
            assertEquals(MirrorMode.HORIZONTAL, mapper.getMirrorMode());
        }

        @Test
        void verticalMirror() {
            mapper.cpuWrite(0xF000, 1);
            mapper.cpuWrite(0xF000, 0);
            assertEquals(MirrorMode.VERTICAL, mapper.getMirrorMode());
        }
    }

    // =========================================================================

    private static byte[] makePrgRom(int banks8k) {
        byte[] rom = new byte[banks8k * 0x2000];
        for (int b = 0; b < banks8k; b++) {
            for (int i = 0; i < 0x2000; i++) {
                rom[b * 0x2000 + i] = (byte) b;
            }
        }
        return rom;
    }

    /** Each 4 KB page is filled with its page index. */
    private static byte[] makeChrRom(int pages4k) {
        byte[] rom = new byte[pages4k * 0x1000];
        for (int p = 0; p < pages4k; p++) {
            for (int i = 0; i < 0x1000; i++) {
                rom[p * 0x1000 + i] = (byte) p;
            }
        }
        return rom;
    }
}
