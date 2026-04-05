package com.nes;

import com.nes.cpu.CPU;
import com.nes.memory.Bus;
import com.nes.memory.Cartridge;
import com.nes.memory.Controller;
import com.nes.ppu.PPU;

/**
 * Top-level NES emulator — owns all hardware components and drives the
 * master clock.
 *
 * Clock relationship: PPU runs at exactly 3× the CPU clock speed.
 * One call to {@link #step()} advances the system by one CPU cycle
 * (= 3 PPU cycles).
 */
public class NES {

    private final CPU  cpu;
    private final PPU  ppu;
    private final Bus  bus;
    private Cartridge  cartridge;

    /** Total CPU cycles executed since the last reset. */
    private long masterClock;

    public NES() {
        ppu = new PPU();
        bus = new Bus(ppu);
        cpu = new CPU(bus);

        // Wire NMI: PPU fires at VBlank start → CPU receives non-maskable interrupt
        ppu.setNmiCallback(cpu::nmi);
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    /** Insert a loaded cartridge into the console. */
    public void insert(Cartridge cartridge) {
        this.cartridge = cartridge;
        bus.setCartridge(cartridge);
        ppu.setCartridge(cartridge);
    }

    /** Attach controllers. */
    public void setControllers(Controller c1, Controller c2) {
        bus.setControllers(c1, c2);
    }

    /** Reset all components to their power-on state. */
    public void reset() {
        ppu.reset();
        cpu.reset();
        masterClock = 0;
    }

    // -------------------------------------------------------------------------
    // Clock
    // -------------------------------------------------------------------------

    /**
     * Advance emulation by one CPU cycle (3 PPU ticks + 1 CPU tick).
     */
    public void step() {
        ppu.tick();
        ppu.tick();
        ppu.tick();
        cpu.tick();
        masterClock++;
    }

    /**
     * Run until the PPU completes one full frame (~89,342 CPU cycles at NTSC).
     * Call this once per display refresh (60 Hz).
     */
    public void stepFrame() {
        do {
            step();
        } while (!ppu.isFrameComplete());
        ppu.clearFrameComplete();
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    /** Returns the PPU frame buffer (256 × 240 ARGB ints). */
    public int[] getFrameBuffer() {
        return ppu.getFrameBuffer();
    }

    /** Total CPU cycles executed since the last reset (1 cycle = 3 PPU ticks). */
    public long getMasterClock() {
        return masterClock;
    }
}
