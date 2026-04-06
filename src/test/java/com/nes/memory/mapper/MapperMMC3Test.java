package com.nes.memory.mapper;

import com.nes.memory.Cartridge.MirrorMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Mapper 4 (MMC3 / TxROM).
 */
class MapperMMC3Test {

    // 8 × 8 KB PRG banks = 64 KB total; each bank filled with its index
    private static final int PRG_BANK8_COUNT = 8;
    private static final byte[] PRG_ROM = makePrgRom(PRG_BANK8_COUNT);

    // 8 × 1 KB CHR pages = 8 KB; each page filled with its index
    private static final int CHR_PAGE_COUNT = 8;
    private static final byte[] CHR_ROM = makeChrRom(CHR_PAGE_COUNT);

    private MapperMMC3 mapper;

    @BeforeEach
    void setUp() {
        mapper = new MapperMMC3(PRG_ROM, CHR_ROM, false);
    }

    // =========================================================================
    // Helper — write to a register via the bank-select / bank-data pair
    // =========================================================================

    private void setReg(int regIndex, int value) {
        mapper.cpuWrite(0x8000, regIndex & 0x07);   // select register
        mapper.cpuWrite(0x8001, value);              // write value
    }

    // =========================================================================

    @Nested
    class InitialState {

        @Test
        void lastBankFixedAtE000() {
            assertEquals(PRG_BANK8_COUNT - 1, mapper.cpuRead(0xE000) & 0xFF);
        }

        @Test
        void secondToLastBankFixedAtC000() {
            assertEquals(PRG_BANK8_COUNT - 2, mapper.cpuRead(0xC000) & 0xFF);
        }

        @Test
        void defaultMirrorVertical() {
            assertEquals(MirrorMode.VERTICAL, mapper.getMirrorMode());
        }

        @Test
        void prgRamReadWrite() {
            mapper.cpuWrite(0x6000, 0xAB);
            assertEquals(0xAB, mapper.cpuRead(0x6000));
        }
    }

    // =========================================================================

    @Nested
    class PrgBankSwitching {

        @Test
        void r6SelectsBank8000InMode0() {
            // PRG mode 0 (bit 6 = 0, default): R6 → $8000
            mapper.cpuWrite(0x8000, 6);  // select R6, mode 0
            mapper.cpuWrite(0x8001, 3);  // bank 3 at $8000
            assertEquals(3, mapper.cpuRead(0x8000) & 0xFF);
        }

        @Test
        void r7AlwaysSelectsA000() {
            setReg(7, 2);
            assertEquals(2, mapper.cpuRead(0xA000) & 0xFF);
        }

        @Test
        void mode1SwapsR6ToC000() {
            // PRG mode 1 (bit 6 = 1): R6 → $C000, second-to-last → $8000
            mapper.cpuWrite(0x8000, 0x40 | 6); // mode 1, select R6
            mapper.cpuWrite(0x8001, 2);         // bank 2 at $C000
            assertEquals(PRG_BANK8_COUNT - 2, mapper.cpuRead(0x8000) & 0xFF); // fixed at $8000
            assertEquals(2, mapper.cpuRead(0xC000) & 0xFF);                    // R6 at $C000
        }

        @Test
        void e000AlwaysLastBank() {
            setReg(6, 1);  // change $8000 bank
            assertEquals(PRG_BANK8_COUNT - 1, mapper.cpuRead(0xE000) & 0xFF);
        }
    }

    // =========================================================================

    @Nested
    class ChrBankSwitching {

        @Test
        void r0SelectsTwoKbAt0000InMode0() {
            // CHR mode 0 (bit 7 = 0, default): R0 = 2 KB at $0000
            setReg(0, 2); // 2 KB block starting at 1 KB page 2 (page 2 & 3)
            assertEquals(2, mapper.ppuRead(0x0000) & 0xFF); // first 1 KB of block
            assertEquals(3, mapper.ppuRead(0x0400) & 0xFF); // second 1 KB of block
        }

        @Test
        void r2SelectsOneKbAt1000InMode0() {
            setReg(2, 5);
            assertEquals(5, mapper.ppuRead(0x1000) & 0xFF);
        }

        @Test
        void chrInversionSwapsHalves() {
            // Set R0=4 (2 KB at $0000) and R2=1 (1 KB at $1000) in mode 0 first
            setReg(0, 4);
            setReg(2, 1);
            // Enable CHR inversion by targeting R6 (PRG register) so R0/R2 stay intact
            mapper.cpuWrite(0x8000, 0x80 | 6); // CHR invert on, target R6
            mapper.cpuWrite(0x8001, 0);         // write 0 to R6 (PRG, doesn't affect CHR)
            // With inversion: 2 KB banks (R0) → $1000, 1 KB banks (R2) → $0000
            assertEquals(1, mapper.ppuRead(0x0000) & 0xFF); // R2 now at $0000
            assertEquals(4, mapper.ppuRead(0x1000) & 0xFF); // R0 now at $1000
        }
    }

    // =========================================================================

    @Nested
    class MirrorModeTests {

        @Test
        void horizontalMirror() {
            mapper.cpuWrite(0xA000, 1);
            assertEquals(MirrorMode.HORIZONTAL, mapper.getMirrorMode());
        }

        @Test
        void verticalMirror() {
            mapper.cpuWrite(0xA000, 1);
            mapper.cpuWrite(0xA000, 0);
            assertEquals(MirrorMode.VERTICAL, mapper.getMirrorMode());
        }
    }

    // =========================================================================

    @Nested
    class IrqTests {

        @Test
        void noIrqByDefault() {
            assertFalse(mapper.irqPending());
        }

        @Test
        void irqFiresAfterCounterReachesZero() {
            mapper.cpuWrite(0xC000, 2);  // latch = 2
            mapper.cpuWrite(0xC001, 0);  // force reload
            mapper.cpuWrite(0xE001, 0);  // enable IRQ

            // Tick 3 times: reload to 2, then 1, then 0 → fires
            mapper.tickScanline(); // counter = 2 (reload)
            assertFalse(mapper.irqPending());
            mapper.tickScanline(); // counter = 1
            assertFalse(mapper.irqPending());
            mapper.tickScanline(); // counter = 0 → IRQ
            assertTrue(mapper.irqPending());
        }

        @Test
        void irqDisableAcknowledges() {
            mapper.cpuWrite(0xC000, 1);
            mapper.cpuWrite(0xC001, 0);
            mapper.cpuWrite(0xE001, 0);
            mapper.tickScanline();
            mapper.tickScanline();
            assertTrue(mapper.irqPending());
            mapper.cpuWrite(0xE000, 0);  // disable + ack
            assertFalse(mapper.irqPending());
        }

        @Test
        void irqDoesNotFireWhenDisabled() {
            mapper.cpuWrite(0xC000, 1);
            mapper.cpuWrite(0xC001, 0);
            // IRQ enable NOT set
            mapper.tickScanline();
            mapper.tickScanline();
            assertFalse(mapper.irqPending());
        }

        @Test
        void irqReloadSetsCounter() {
            mapper.cpuWrite(0xC000, 5);  // latch = 5
            mapper.cpuWrite(0xC001, 0);  // request reload
            mapper.cpuWrite(0xE001, 0);  // enable
            mapper.tickScanline();       // tick 1: reload → counter = 5
            // Ticks 2-5: counter goes 4, 3, 2, 1 — no IRQ yet
            for (int i = 0; i < 4; i++) mapper.tickScanline();
            assertFalse(mapper.irqPending());
            mapper.tickScanline();       // tick 6: counter → 0 → IRQ fires
            assertTrue(mapper.irqPending());
        }
    }

    // =========================================================================

    @Nested
    class ChrRamTests {

        @Test
        void chrRamWritableAndReadable() {
            MapperMMC3 ramMapper = new MapperMMC3(PRG_ROM, null, false);
            ramMapper.ppuWrite(0x0000, 0xCC);
            assertEquals(0xCC, ramMapper.ppuRead(0x0000));
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

    private static byte[] makeChrRom(int pages1k) {
        byte[] rom = new byte[pages1k * 0x400];
        for (int p = 0; p < pages1k; p++) {
            for (int i = 0; i < 0x400; i++) {
                rom[p * 0x400 + i] = (byte) p;
            }
        }
        return rom;
    }
}
