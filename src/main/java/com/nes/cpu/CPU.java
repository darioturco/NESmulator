package com.nes.cpu;

import com.nes.memory.Bus;

/**
 * Ricoh 2A03 CPU — a MOS 6502 variant (no decimal mode).
 *
 * All byte values are stored as int and masked with 0xFF.
 * All addresses are stored as int and masked with 0xFFFF.
 */
public class CPU {

    // Registers
    private int a;    // Accumulator      (8-bit)
    private int x;    // Index X          (8-bit)
    private int y;    // Index Y          (8-bit)
    private int sp;   // Stack pointer    (8-bit, stack lives at $0100-$01FF)
    private int pc;   // Program counter  (16-bit)
    private int p;    // Status flags     (8-bit, see Flags)

    // Remaining cycles for the current instruction
    private int cycles;

    private final Bus bus;

    public CPU(Bus bus) {
        this.bus = bus;
    }

    // -------------------------------------------------------------------------
    // Interrupts
    // -------------------------------------------------------------------------

    /** Execute the RESET sequence: load PC from $FFFC/$FFFD. */
    public void reset() {
    }

    /** Trigger a Non-Maskable Interrupt (called by PPU at VBLANK). */
    public void nmi() {
    }

    /** Trigger a maskable Interrupt Request (ignored if I flag is set). */
    public void irq() {
    }

    // -------------------------------------------------------------------------
    // Clock
    // -------------------------------------------------------------------------

    /**
     * Advance the CPU by one clock cycle.
     * When the cycle count of the current instruction reaches zero,
     * fetch and decode the next opcode.
     */
    public void tick() {
    }

    // -------------------------------------------------------------------------
    // Memory access (delegates to Bus)
    // -------------------------------------------------------------------------

    /** Read one byte from the bus. Returns value in range [0, 255]. */
    private int read(int addr) {
        return 0;
    }

    /** Write one byte to the bus. Data is masked to 8 bits. */
    private void write(int addr, int data) {
    }

    /** Read a 16-bit little-endian word from the bus. */
    private int readWord(int addr) {
        return 0;
    }

    // -------------------------------------------------------------------------
    // Stack helpers
    // -------------------------------------------------------------------------

    private void push(int data) {
    }

    private int pop() {
        return 0;
    }

    private void pushWord(int data) {
    }

    private int popWord() {
        return 0;
    }

    // -------------------------------------------------------------------------
    // Flag helpers
    // -------------------------------------------------------------------------

    private void setFlag(int flag, boolean value) {
    }

    private boolean getFlag(int flag) {
        return false;
    }
}
