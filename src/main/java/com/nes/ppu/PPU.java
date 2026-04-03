package com.nes.ppu;

import com.nes.memory.Cartridge;

/**
 * Ricoh 2C02 Picture Processing Unit.
 *
 * Produces a 256×240 pixel frame. Runs at 3× the CPU clock speed.
 * Each frame = 262 scanlines × 341 cycles = 89,342 PPU cycles.
 */
public class PPU {

    public static final int SCREEN_WIDTH  = 256;
    public static final int SCREEN_HEIGHT = 240;

    // Current position in the frame
    private int scanline;  // 0–261  (0–239 visible, 240 post, 241–260 VBLANK, 261 pre)
    private int cycle;     // 0–340

    // Internal VRAM (2KB nametables + 32B palette)
    private final byte[] vram    = new byte[2048];
    private final byte[] palette = new byte[32];

    // Object Attribute Memory (64 sprites × 4 bytes)
    private final byte[] oam = new byte[256];

    // Output frame buffer (ARGB, one int per pixel)
    private final int[] frameBuffer = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
    private boolean frameComplete;

    // PPU internal registers (Loopy registers)
    private int v;     // Current VRAM address  (15-bit)
    private int t;     // Temporary VRAM address (15-bit)
    private int x;     // Fine X scroll          (3-bit)
    private boolean w; // Write toggle

    // PPU control/mask/status (mapped at $2000-$2007 via Bus)
    private int ctrl;   // PPUCTRL   $2000
    private int mask;   // PPUMASK   $2001
    private int status; // PPUSTATUS $2002
    private int oamAddr;// OAMADDR   $2003

    // Data buffer for PPUDATA reads (one-cycle delay)
    private int dataBuffer;

    private Cartridge cartridge;

    // Reference back to the CPU for firing NMI
    private Runnable nmiCallback;

    public PPU() {
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void reset() {
    }

    public void setCartridge(Cartridge cartridge) {
    }

    /** Provide the callback the PPU will invoke when it triggers an NMI. */
    public void setNmiCallback(Runnable nmiCallback) {
        this.nmiCallback = nmiCallback;
    }

    // -------------------------------------------------------------------------
    // Clock
    // -------------------------------------------------------------------------

    /** Advance the PPU by one clock cycle. */
    public void tick() {
    }

    // -------------------------------------------------------------------------
    // CPU-facing register access (routed from Bus at $2000-$2007)
    // -------------------------------------------------------------------------

    /** CPU reads a PPU register (reg = addr & 0x07). Returns value [0, 255]. */
    public int readRegister(int reg) {
        return 0;
    }

    /** CPU writes a PPU register (reg = addr & 0x07). */
    public void writeRegister(int reg, int data) {
    }

    /** DMA: CPU writes 256 bytes directly into OAM ($4014 write). */
    public void writeDma(byte[] page) {
    }

    // -------------------------------------------------------------------------
    // Internal VRAM access (used by the PPU renderer)
    // -------------------------------------------------------------------------

    /** Read one byte from PPU address space [0, 255]. */
    private int readVram(int addr) {
        return 0;
    }

    /** Write one byte to PPU address space. */
    private void writeVram(int addr, int data) {
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    /** Returns the completed frame buffer (SCREEN_WIDTH * SCREEN_HEIGHT ARGB ints). */
    public int[] getFrameBuffer() {
        return frameBuffer;
    }

    /** True once per frame when the PPU has finished rendering scanline 239. */
    public boolean isFrameComplete() {
        return frameComplete;
    }

    /** Clear the frame-complete flag (call after consuming the frame). */
    public void clearFrameComplete() {
        frameComplete = false;
    }
}
