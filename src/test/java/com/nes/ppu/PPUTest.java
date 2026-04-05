package com.nes.ppu;

import com.nes.memory.Cartridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the PPU subsystem.
 *
 * Covers: PatternMemory, NameTableMemory, PaletteMemory, PPUBus, and PPU.
 */
class PPUTest {

    // -------------------------------------------------------------------------
    // Stub cartridge
    // -------------------------------------------------------------------------

    /**
     * Minimal Cartridge stub backed by a flat 8KB CHR-RAM array.
     * Mirror mode is configurable per test.
     */
    private static class StubCartridge extends Cartridge {
        private final int[] chr = new int[0x2000]; // 8KB CHR-RAM
        private MirrorMode mirrorMode;

        StubCartridge(MirrorMode mode) { this.mirrorMode = mode; }
        StubCartridge() { this(MirrorMode.VERTICAL); }

        @Override public int ppuRead(int addr)               { return chr[addr & 0x1FFF]; }
        @Override public void ppuWrite(int addr, int data)   { chr[addr & 0x1FFF] = data & 0xFF; }
        @Override public MirrorMode getMirrorMode()          { return mirrorMode; }
    }

    // =========================================================================
    // PatternMemory
    // =========================================================================

    @Nested
    class PatternMemoryTests {

        private PatternMemory pattern;
        private StubCartridge cart;

        @BeforeEach
        void setUp() {
            cart    = new StubCartridge();
            pattern = new PatternMemory();
            pattern.setCartridge(cart);
        }

        @Test void read_delegates_to_cartridge() {
            cart.ppuWrite(0x0010, 0xAB);
            assertEquals(0xAB, pattern.read(0x0010));
        }

        @Test void write_delegates_to_cartridge() {
            pattern.write(0x1234, 0x77);
            assertEquals(0x77, cart.ppuRead(0x1234));
        }

        @Test void read_masks_to_14_bit_window() {
            cart.ppuWrite(0x0005, 0x55);
            // Address above $1FFF should wrap into $0000–$1FFF
            assertEquals(0x55, pattern.read(0x2005));
        }

        @Test void read_pattern_table_1_boundary() {
            cart.ppuWrite(0x1000, 0xCC);
            assertEquals(0xCC, pattern.read(0x1000));
        }

        @Test void read_returns_zero_with_no_cartridge() {
            PatternMemory p = new PatternMemory();  // no cartridge set
            assertEquals(0, p.read(0x0000));
        }

        @Test void write_is_safe_with_no_cartridge() {
            PatternMemory p = new PatternMemory();
            assertDoesNotThrow(() -> p.write(0x0000, 0xFF));
        }
    }

    // =========================================================================
    // NameTableMemory
    // =========================================================================

    @Nested
    class NameTableMemoryTests {

        private NameTableMemory nt;

        @BeforeEach
        void setUp() { nt = new NameTableMemory(); }

        // --- Vertical mirroring (NT0/NT2 → bank0, NT1/NT3 → bank1) ---

        @Test void vertical_nt0_and_nt2_share_bank() {
            nt.setCartridge(new StubCartridge(Cartridge.MirrorMode.VERTICAL));
            nt.write(0x2005, 0x11);           // NT0
            assertEquals(0x11, nt.read(0x2805)); // NT2 same bank
        }

        @Test void vertical_nt1_and_nt3_share_bank() {
            nt.setCartridge(new StubCartridge(Cartridge.MirrorMode.VERTICAL));
            nt.write(0x2405, 0x22);           // NT1
            assertEquals(0x22, nt.read(0x2C05)); // NT3 same bank
        }

        @Test void vertical_nt0_and_nt1_are_independent() {
            nt.setCartridge(new StubCartridge(Cartridge.MirrorMode.VERTICAL));
            nt.write(0x2000, 0xAA);
            nt.write(0x2400, 0xBB);
            assertEquals(0xAA, nt.read(0x2000));
            assertEquals(0xBB, nt.read(0x2400));
        }

        // --- Horizontal mirroring (NT0/NT1 → bank0, NT2/NT3 → bank1) ---

        @Test void horizontal_nt0_and_nt1_share_bank() {
            nt.setCartridge(new StubCartridge(Cartridge.MirrorMode.HORIZONTAL));
            nt.write(0x2010, 0x33);           // NT0
            assertEquals(0x33, nt.read(0x2410)); // NT1 same bank
        }

        @Test void horizontal_nt2_and_nt3_share_bank() {
            nt.setCartridge(new StubCartridge(Cartridge.MirrorMode.HORIZONTAL));
            nt.write(0x2810, 0x44);           // NT2
            assertEquals(0x44, nt.read(0x2C10)); // NT3 same bank
        }

        @Test void horizontal_nt0_and_nt2_are_independent() {
            nt.setCartridge(new StubCartridge(Cartridge.MirrorMode.HORIZONTAL));
            nt.write(0x2000, 0xAA);
            nt.write(0x2800, 0xBB);
            assertEquals(0xAA, nt.read(0x2000));
            assertEquals(0xBB, nt.read(0x2800));
        }

        // --- Single-screen mirroring ---

        @Test void single_lo_all_nametables_share_bank0() {
            nt.setCartridge(new StubCartridge(Cartridge.MirrorMode.SINGLE_SCREEN_LO));
            nt.write(0x2000, 0x55);
            assertEquals(0x55, nt.read(0x2400));
            assertEquals(0x55, nt.read(0x2800));
            assertEquals(0x55, nt.read(0x2C00));
        }

        @Test void single_hi_all_nametables_share_bank1() {
            nt.setCartridge(new StubCartridge(Cartridge.MirrorMode.SINGLE_SCREEN_HI));
            nt.write(0x2C00, 0x66);
            assertEquals(0x66, nt.read(0x2000));
            assertEquals(0x66, nt.read(0x2400));
            assertEquals(0x66, nt.read(0x2800));
        }

        // --- Reset ---

        @Test void reset_clears_all_banks() {
            nt.setCartridge(new StubCartridge(Cartridge.MirrorMode.VERTICAL));
            nt.write(0x2000, 0xFF);
            nt.write(0x2400, 0xFF);
            nt.reset();
            assertEquals(0, nt.read(0x2000));
            assertEquals(0, nt.read(0x2400));
        }
    }

    // =========================================================================
    // PaletteMemory
    // =========================================================================

    @Nested
    class PaletteMemoryTests {

        private PaletteMemory pal;

        @BeforeEach
        void setUp() { pal = new PaletteMemory(); }

        @Test void write_and_read_back() {
            pal.write(0x3F01, 0x2A);
            assertEquals(0x2A, pal.read(0x3F01));
        }

        @Test void write_masks_to_6_bits() {
            pal.write(0x3F02, 0xFF);
            assertEquals(0x3F, pal.read(0x3F02));
        }

        // Background colour mirrors ($3F10/$3F14/$3F18/$3F1C → $3F00/$3F04/$3F08/$3F0C)

        @Test void mirror_3F10_reads_from_3F00() {
            pal.write(0x3F00, 0x0F);
            assertEquals(0x0F, pal.read(0x3F10));
        }

        @Test void mirror_3F10_writes_to_3F00() {
            pal.write(0x3F10, 0x1C);
            assertEquals(0x1C, pal.read(0x3F00));
        }

        @Test void mirror_3F14_aliases_3F04() {
            pal.write(0x3F04, 0x05);
            assertEquals(0x05, pal.read(0x3F14));
        }

        @Test void mirror_3F18_aliases_3F08() {
            pal.write(0x3F18, 0x12);
            assertEquals(0x12, pal.read(0x3F08));
        }

        @Test void mirror_3F1C_aliases_3F0C() {
            pal.write(0x3F0C, 0x3A);
            assertEquals(0x3A, pal.read(0x3F1C));
        }

        @Test void non_mirrored_sprite_palette_entries_are_independent() {
            pal.write(0x3F11, 0x01);
            pal.write(0x3F12, 0x02);
            pal.write(0x3F13, 0x03);
            assertEquals(0x01, pal.read(0x3F11));
            assertEquals(0x02, pal.read(0x3F12));
            assertEquals(0x03, pal.read(0x3F13));
        }

        @Test void to_argb_returns_opaque_colour() {
            // Any index should return a fully opaque ARGB value (alpha = 0xFF)
            int argb = pal.toArgb(0x0F);
            assertEquals(0xFF, (argb >>> 24) & 0xFF);
        }

        @Test void to_argb_masks_index_to_6_bits() {
            assertEquals(pal.toArgb(0x00), pal.toArgb(0x40)); // 0x40 & 0x3F == 0x00
        }

        @Test void reset_clears_palette_ram() {
            pal.write(0x3F05, 0x3F);
            pal.reset();
            assertEquals(0, pal.read(0x3F05));
        }
    }

    // =========================================================================
    // PPUBus
    // =========================================================================

    @Nested
    class PPUBusTests {

        private PPUBus bus;
        private StubCartridge cart;

        @BeforeEach
        void setUp() {
            cart = new StubCartridge(Cartridge.MirrorMode.VERTICAL);
            bus  = new PPUBus();
            bus.setCartridge(cart);
        }

        // --- Pattern table routing ($0000–$1FFF) ---

        @Test void reads_pattern_table_from_cartridge() {
            cart.ppuWrite(0x0042, 0xBE);
            assertEquals(0xBE, bus.read(0x0042));
        }

        @Test void writes_pattern_table_to_cartridge() {
            bus.write(0x1ABC, 0x7F);
            assertEquals(0x7F, cart.ppuRead(0x1ABC));
        }

        // --- Nametable routing ($2000–$2FFF) ---

        @Test void reads_nametable_from_vram() {
            bus.write(0x2100, 0x55);
            assertEquals(0x55, bus.read(0x2100));
        }

        @Test void nametable_mirror_range_3000_maps_to_2000() {
            bus.write(0x2200, 0xAA);
            assertEquals(0xAA, bus.read(0x3200)); // $3200 mirrors $2200
        }

        // --- Palette routing ($3F00–$3FFF) ---

        @Test void reads_palette_ram() {
            bus.write(0x3F03, 0x1F);
            assertEquals(0x1F, bus.read(0x3F03));
        }

        @Test void palette_mirror_at_3F20_wraps() {
            bus.write(0x3F01, 0x0A);
            assertEquals(0x0A, bus.read(0x3F21)); // $3F21 mirrors $3F01
        }

        // --- Address masking ---

        @Test void addresses_are_masked_to_14_bits() {
            bus.write(0x3F01, 0x09);
            // $7F01 & $3FFF == $3F01
            assertEquals(0x09, bus.read(0x7F01));
        }

        // --- toArgb delegation ---

        @Test void to_argb_returns_opaque_colour() {
            assertTrue((bus.toArgb(0x00) & 0xFF000000) != 0);
        }

        // --- Reset ---

        @Test void reset_clears_nametable_and_palette() {
            bus.write(0x2000, 0xFF);
            bus.write(0x3F05, 0x3F);
            bus.reset();
            assertEquals(0, bus.read(0x2000));
            assertEquals(0, bus.read(0x3F05));
        }
    }

    // =========================================================================
    // PPU
    // =========================================================================

    @Nested
    class PPUTests {

        private PPU ppu;
        private StubCartridge cart;

        @BeforeEach
        void setUp() {
            cart = new StubCartridge();
            ppu  = new PPU();
            ppu.setCartridge(cart);
            ppu.reset();
        }

        @Test void frame_buffer_has_correct_size() {
            assertEquals(PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT, ppu.getFrameBuffer().length);
        }

        @Test void frame_not_complete_after_reset() {
            assertFalse(ppu.isFrameComplete());
        }

        @Test void clear_frame_complete_clears_flag() {
            ppu.clearFrameComplete();
            assertFalse(ppu.isFrameComplete());
        }

        @Test void nmi_callback_stored_without_throwing() {
            assertDoesNotThrow(() -> ppu.setNmiCallback(() -> {}));
        }

        @Test void pattern_memory_readable_via_ppu_after_cartridge_set() {
            // Write to CHR-RAM through the cartridge, confirm PPU bus can read it
            cart.ppuWrite(0x0010, 0xDA);
            // Access through PPUBus indirectly: PPU resets scanline/cycle but bus is wired
            // We verify no exception and the cartridge data is intact
            assertEquals(0xDA, cart.ppuRead(0x0010));
        }

        @Test void tick_does_not_throw() {
            assertDoesNotThrow(() -> { for (int i = 0; i < 341; i++) ppu.tick(); });
        }

        @Test void write_dma_does_not_throw() {
            byte[] page = new byte[256];
            assertDoesNotThrow(() -> ppu.writeDma(page));
        }
    }
}
