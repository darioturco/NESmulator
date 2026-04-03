package com.nes.cpu;

/**
 * Bit masks for the CPU status register (P).
 *
 * 7  bit  0
 * ---- ----
 * N V _ B D I Z C
 * | | | | | | | |
 * | | | | | | | +-- Carry
 * | | | | | | +---- Zero
 * | | | | | +------ Interrupt Disable
 * | | | | +-------- Decimal (unused on NES)
 * | | | +---------- Break
 * | | +------------ (unused, always 1)
 * | +-------------- Overflow
 * +---------------- Negative
 */
public final class Flags {

    public static final int C = 1 << 0; // Carry
    public static final int Z = 1 << 1; // Zero
    public static final int I = 1 << 2; // Interrupt Disable
    public static final int D = 1 << 3; // Decimal (unused)
    public static final int B = 1 << 4; // Break
    public static final int U = 1 << 5; // Unused (always 1)
    public static final int V = 1 << 6; // Overflow
    public static final int N = 1 << 7; // Negative

    private Flags() {}
}
