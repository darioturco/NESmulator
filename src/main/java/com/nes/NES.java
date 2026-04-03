package com.nes;

import com.nes.cpu.CPU;
import com.nes.memory.Bus;
import com.nes.memory.Cartridge;
import com.nes.ppu.PPU;

public class NES {

    private final CPU cpu;
    private final PPU ppu;
    private final Bus bus;
    private Cartridge cartridge;

    public NES() {
        this.ppu = new PPU();
        this.bus = new Bus(ppu);
        this.cpu = new CPU(bus);
    }

    /** Load a cartridge into the console. */
    public void insert(Cartridge cartridge) {
    }

    /** Reset all components to their initial state. */
    public void reset() {
    }

    /**
     * Advance emulation by one CPU cycle (3 PPU ticks + 1 CPU tick).
     */
    public void step() {
    }

    /**
     * Run until the PPU signals a completed frame.
     * Call this once per display refresh (~60 times per second).
     */
    public void stepFrame() {
    }

    /** Returns the PPU's completed frame buffer (256 * 240 ARGB pixels). */
    public int[] getFrameBuffer() {
        return null;
    }
}
