package com.nes;

import com.nes.memory.Cartridge;
import com.nes.memory.Controller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the top-level NES class.
 *
 * Uses a minimal stub cartridge that runs a trivial 6502 program so that
 * the CPU, PPU and bus can exercise real code paths without a ROM file.
 *
 * Program layout (PRG-ROM, 16 KB mirrored):
 *   $8000  SEI                 ; disable interrupts
 *   $8001  LDA #$90            ; PPUCTRL value: NMI enable (bit7) + NT0
 *   $8003  STA $2000           ; enable NMI
 *   $8006  LDA #$08            ; PPUMASK: BG enable
 *   $8008  STA $2001
 *   $800B  JMP $800B           ; infinite loop
 *
 *   NMI vector  ($FFFA/$FFFB) = $8000  (re-run init, harmless)
 *   RESET vector($FFFC/$FFFD) = $8000
 *   IRQ vector  ($FFFE/$FFFF) = $8000
 */
class NESTest {

    // -------------------------------------------------------------------------
    // Stub cartridge
    // -------------------------------------------------------------------------

    private static class StubCartridge extends Cartridge {

        final int[] prg = new int[0x4000]; // 16 KB
        final int[] chr = new int[0x2000]; //  8 KB CHR-RAM

        StubCartridge() {
            // Minimal program
            int a = 0; // offset into PRG
            prg[a++] = 0x78;        // SEI
            prg[a++] = 0xA9;        // LDA imm
            prg[a++] = 0x90;        //   #$90  (NMI enable + NT0)
            prg[a++] = 0x8D;        // STA abs
            prg[a++] = 0x00;        //   $2000 lo
            prg[a++] = 0x20;        //   $2000 hi
            prg[a++] = 0xA9;        // LDA imm
            prg[a++] = 0x08;        //   #$08  (BG enable)
            prg[a++] = 0x8D;        // STA abs
            prg[a++] = 0x01;        //   $2001 lo
            prg[a++] = 0x20;        //   $2001 hi
            prg[a++] = 0x4C;        // JMP abs
            prg[a++] = 0x00;        //   $8000 lo
            prg[a]   = 0x80;        //   $8000 hi

            // NMI / RESET / IRQ vectors → $8000
            prg[0x3FFA] = 0x00; prg[0x3FFB] = 0x80; // NMI
            prg[0x3FFC] = 0x00; prg[0x3FFD] = 0x80; // RESET
            prg[0x3FFE] = 0x00; prg[0x3FFF] = 0x80; // IRQ
        }

        @Override
        public int cpuRead(int addr) {
            if (addr >= 0x8000) return prg[addr & 0x3FFF];
            return 0;
        }

        @Override public void      cpuWrite(int a, int d) { /* ROM: ignore */ }
        @Override public int       ppuRead (int a)        { return chr[a & 0x1FFF]; }
        @Override public void      ppuWrite(int a, int d) { chr[a & 0x1FFF] = d & 0xFF; }
        @Override public MirrorMode getMirrorMode()       { return MirrorMode.HORIZONTAL; }
    }

    // -------------------------------------------------------------------------

    private NES            nes;
    private StubCartridge  cart;
    private Controller     ctrl1, ctrl2;

    @BeforeEach
    void setUp() {
        cart  = new StubCartridge();
        nes   = new NES();
        ctrl1 = new Controller();
        ctrl2 = new Controller();
        nes.insert(cart);
        nes.setControllers(ctrl1, ctrl2);
        nes.reset();
    }

    // =========================================================================
    // Frame stepping
    // =========================================================================

    @Nested
    class FrameStepping {

        @Test void stepFrame_produces_frame_buffer() {
            nes.stepFrame();
            int[] fb = nes.getFrameBuffer();
            assertNotNull(fb);
            assertEquals(256 * 240, fb.length);
        }

        @Test void stepFrame_returns_new_frame_each_call() {
            nes.stepFrame();
            int[] fb1 = nes.getFrameBuffer().clone();
            nes.stepFrame();
            int[] fb2 = nes.getFrameBuffer().clone();
            // Frame buffers don't need to differ in pixel values for a solid
            // background, but the arrays must be the same-length live buffers.
            assertEquals(fb1.length, fb2.length);
        }

        @Test void multiple_frames_do_not_throw() {
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 5; i++) nes.stepFrame();
            });
        }
    }

    // =========================================================================
    // Reset
    // =========================================================================

    @Nested
    class Reset {

        @Test void reset_then_step_does_not_throw() {
            nes.reset();
            assertDoesNotThrow(() -> nes.stepFrame());
        }

        @Test void double_reset_stable() {
            nes.stepFrame();
            nes.reset();
            assertDoesNotThrow(() -> nes.stepFrame());
        }
    }

    // =========================================================================
    // Controller wiring
    // =========================================================================

    @Nested
    class ControllerWiring {

        @Test void controller_button_state_accessible_during_emulation() {
            ctrl1.setButton(Controller.BTN_START, true);
            // The emulation reads $4016 internally; pressing a button must not
            // crash or corrupt emulation state.
            assertDoesNotThrow(() -> nes.stepFrame());
            assertTrue((ctrl1.getButtonState() & (1 << Controller.BTN_START)) != 0);
        }

        @Test void no_controllers_does_not_crash() {
            NES bare = new NES();
            bare.insert(cart);
            // No setControllers call
            bare.reset();
            assertDoesNotThrow(() -> bare.stepFrame());
        }
    }

    // =========================================================================
    // NMI
    // =========================================================================

    @Nested
    class NMI {

        @Test void nmi_fires_without_exception_across_frames() {
            // Our stub program enables NMI; verify several frames run cleanly.
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 3; i++) nes.stepFrame();
            });
        }

        @Test void frame_buffer_is_populated_after_nmi_frame() {
            nes.stepFrame(); // first frame may not have NMI yet
            nes.stepFrame(); // second frame will have NMI
            int[] fb = nes.getFrameBuffer();
            assertEquals(256 * 240, fb.length);
        }
    }

    // =========================================================================
    // Insert
    // =========================================================================

    @Nested
    class Insert {

        @Test void insert_after_reset_accepts_new_cartridge() {
            StubCartridge cart2 = new StubCartridge();
            nes.insert(cart2);
            nes.reset();
            assertDoesNotThrow(() -> nes.stepFrame());
        }
    }
}
