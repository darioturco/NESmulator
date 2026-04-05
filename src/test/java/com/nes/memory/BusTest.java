package com.nes.memory;

import com.nes.ppu.PPU;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the CPU bus address decoder.
 *
 * Uses a stub PPU that records register reads/writes and stub
 * Cartridge/Controller objects to verify routing without side-effects.
 */
class BusTest {

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    /** Records the last register index and value written; tracks read calls. */
    private static class StubPPU extends PPU {
        int lastWriteReg = -1, lastWriteVal = -1;
        int lastReadReg  = -1;
        int readReturnValue = 0x42;

        @Override public void writeRegister(int reg, int data) {
            lastWriteReg = reg; lastWriteVal = data;
        }
        @Override public int readRegister(int reg) {
            lastReadReg = reg; return readReturnValue;
        }
        @Override public void writeDma(byte[] page) { /* ignore */ }
    }

    /** Minimal Cartridge: flat 32KB PRG-ROM (all 0xAB) + 8KB CHR-ROM (all 0xCD). */
    private static class StubCartridge extends Cartridge {
        private final int[] prg = new int[0x8000];
        private final int[] chr = new int[0x2000];

        StubCartridge() {
            for (int i = 0; i < prg.length; i++) prg[i] = 0xAB;
            for (int i = 0; i < chr.length; i++) chr[i] = 0xCD;
        }

        @Override public int  cpuRead (int addr)        { return prg[addr & 0x7FFF]; }
        @Override public void cpuWrite(int addr, int d) { prg[addr & 0x7FFF] = d & 0xFF; }
        @Override public int  ppuRead (int addr)        { return chr[addr & 0x1FFF]; }
        @Override public void ppuWrite(int addr, int d) { chr[addr & 0x1FFF] = d & 0xFF; }
        @Override public MirrorMode getMirrorMode()     { return MirrorMode.HORIZONTAL; }
    }

    // -------------------------------------------------------------------------

    private StubPPU       ppu;
    private Bus           bus;
    private StubCartridge cart;
    private Controller    ctrl1, ctrl2;

    @BeforeEach
    void setUp() {
        ppu   = new StubPPU();
        bus   = new Bus(ppu);
        cart  = new StubCartridge();
        ctrl1 = new Controller();
        ctrl2 = new Controller();
        bus.setCartridge(cart);
        bus.setControllers(ctrl1, ctrl2);
    }

    // =========================================================================
    // Internal RAM  $0000–$1FFF
    // =========================================================================

    @Nested
    class InternalRAM {

        @Test void write_and_read_back() {
            bus.write(0x0100, 0x55);
            assertEquals(0x55, bus.read(0x0100));
        }

        @Test void mirrored_every_2KB() {
            bus.write(0x0000, 0x11);
            assertEquals(0x11, bus.read(0x0800)); // mirror 1
            assertEquals(0x11, bus.read(0x1000)); // mirror 2
            assertEquals(0x11, bus.read(0x1800)); // mirror 3
        }

        @Test void high_address_bits_masked() {
            bus.write(0x1FFF, 0x77);
            // 0x1FFF & 0x07FF = 0x07FF
            assertEquals(0x77, bus.read(0x07FF));
        }

        @Test void values_8bit_masked() {
            bus.write(0x0000, 0x1FF); // only low 8 bits stored
            assertEquals(0xFF, bus.read(0x0000));
        }
    }

    // =========================================================================
    // PPU registers  $2000–$3FFF
    // =========================================================================

    @Nested
    class PPURegisters {

        @Test void write_routes_to_ppu_register() {
            bus.write(0x2001, 0x18);
            assertEquals(1,    ppu.lastWriteReg);
            assertEquals(0x18, ppu.lastWriteVal);
        }

        @Test void read_routes_to_ppu_register() {
            ppu.readReturnValue = 0x7B;
            int val = bus.read(0x2002);
            assertEquals(2,    ppu.lastReadReg);
            assertEquals(0x7B, val);
        }

        @Test void ppu_registers_mirrored_every_8_bytes() {
            bus.write(0x2008, 0xAA); // mirrors $2000 (reg 0)
            assertEquals(0, ppu.lastWriteReg);
            assertEquals(0xAA, ppu.lastWriteVal);

            bus.write(0x3FFF, 0xBB); // mirrors $2007 (reg 7)
            assertEquals(7, ppu.lastWriteReg);
        }
    }

    // =========================================================================
    // Controllers  $4016 / $4017
    // =========================================================================

    @Nested
    class Controllers {

        @Test void write_4016_strobes_both_controllers() {
            ctrl1.setButton(Controller.BTN_A, true);
            bus.write(0x4016, 1); // strobe high
            bus.write(0x4016, 0); // strobe low → latch
            assertEquals(1, bus.read(0x4016) & 1); // A pressed
        }

        @Test void read_4017_reads_ctrl2() {
            ctrl2.setButton(Controller.BTN_B, true);
            bus.write(0x4016, 1);
            bus.write(0x4016, 0); // latch
            bus.read(0x4017); // A bit (0)
            assertEquals(1, bus.read(0x4017) & 1); // B bit
        }

        @Test void read_4016_without_controllers_returns_0() {
            Bus bare = new Bus(ppu);
            assertEquals(0, bare.read(0x4016));
            assertEquals(0, bare.read(0x4017));
        }
    }

    // =========================================================================
    // Cartridge  $4020–$FFFF
    // =========================================================================

    @Nested
    class CartridgeSpace {

        @Test void read_from_cart_space() {
            assertEquals(0xAB, bus.read(0x8000));
        }

        @Test void write_to_cart_space() {
            bus.write(0x8000, 0x55);
            assertEquals(0x55, bus.read(0x8000));
        }

        @Test void below_4020_is_not_cart() {
            // $4019 is not routed to cartridge; reads return 0
            assertEquals(0, bus.read(0x4019));
        }

        @Test void read_word_little_endian() {
            cart.cpuWrite(0x8000, 0x34);
            cart.cpuWrite(0x8001, 0x12);
            assertEquals(0x1234, bus.readWord(0x8000));
        }
    }

    // =========================================================================
    // OAM DMA  $4014
    // =========================================================================

    @Nested
    class OamDma {

        @Test void write_4014_copies_page_to_ppu() {
            // Write 0xAA into page $02 (CPU addresses $0200–$02FF)
            for (int i = 0; i < 256; i++) bus.write(0x0200 + i, i & 0xFF);

            // Override writeDma to capture the page
            final byte[][] captured = {null};
            Bus captureBus = new Bus(new StubPPU() {
                @Override public void writeDma(byte[] page) { captured[0] = page.clone(); }
            });
            captureBus.setCartridge(cart);
            for (int i = 0; i < 256; i++) captureBus.write(0x0200 + i, i & 0xFF);
            captureBus.write(0x4014, 0x02); // DMA from page $02

            assertNotNull(captured[0]);
            assertEquals(256, captured[0].length);
            for (int i = 0; i < 256; i++) {
                assertEquals((byte)(i & 0xFF), captured[0][i],
                        "DMA byte " + i + " mismatch");
            }
        }
    }
}
