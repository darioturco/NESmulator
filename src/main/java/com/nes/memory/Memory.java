package com.nes.memory;

/**
 * NES internal RAM: 2KB ($0000–$07FF), mirrored three times up to $1FFF.
 */
public class Memory {

    /** 2KB physical RAM. */
    private final int[] ram = new int[0x0800];

    public Memory() {
    }

    /**
     * Read a byte from RAM, applying the $07FF mirror mask.
     *
     * @param address CPU address in the range $0000–$1FFF
     * @return byte value (0x00–0xFF)
     */
    public int read(int address) {
        return ram[address & 0x07FF];
    }

    /**
     * Write a byte to RAM, applying the $07FF mirror mask.
     *
     * @param address CPU address in the range $0000–$1FFF
     * @param value   byte value (0x00–0xFF)
     */
    public void write(int address, int value) {
        ram[address & 0x07FF] = value & 0xFF;
    }

    /** Reset all RAM to zero. */
    public void reset() {
        java.util.Arrays.fill(ram, 0);
    }
}
