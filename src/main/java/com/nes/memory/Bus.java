package com.nes.memory;

import com.nes.ppu.PPU;

/**
 * CPU address bus — routes reads and writes to the correct component.
 *
 * Address map:
 *   $0000–$1FFF  →  Internal RAM (2KB mirrored every $0800)
 *   $2000–$3FFF  →  PPU registers (8 registers mirrored every 8 bytes)
 *   $4000–$4013  →  APU registers  (future)
 *   $4014        →  OAM DMA
 *   $4015        →  APU status     (future)
 *   $4016        →  Controller 1
 *   $4017        →  Controller 2 / APU frame counter
 *   $4020–$FFFF  →  Cartridge (PRG-ROM / PRG-RAM via mapper)
 */
public class Bus {

    // 2KB of internal CPU RAM, mirrored to fill $0000-$1FFF
    private final byte[] ram = new byte[2048];

    private final PPU ppu;
    private Cartridge cartridge;
    private Controller controller1;
    private Controller controller2;

    public Bus(PPU ppu) {
        this.ppu = ppu;
    }

    public void setCartridge(Cartridge cartridge) {
    }

    public void setControllers(Controller controller1, Controller controller2) {
    }

    // -------------------------------------------------------------------------
    // CPU read / write
    // -------------------------------------------------------------------------

    /** Read one byte from the CPU address space. Returns value in [0, 255]. */
    public int read(int addr) {
        return 0;
    }

    /** Write one byte to the CPU address space. Data is masked to 8 bits. */
    public void write(int addr, int data) {
    }

    /** Read a 16-bit little-endian word. */
    public int readWord(int addr) {
        return 0;
    }
}
