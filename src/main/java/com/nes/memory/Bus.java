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
 *   $4016        →  Controller 1 read / strobe write
 *   $4017        →  Controller 2 read / APU frame counter
 *   $4020–$FFFF  →  Cartridge (PRG-ROM / PRG-RAM via mapper)
 */
public class Bus {

    // 2KB of internal CPU RAM, mirrored to fill $0000-$1FFF
    private final byte[] ram = new byte[2048];

    private final PPU ppu;
    private Cartridge   cartridge;
    private Controller  controller1;
    private Controller  controller2;

    public Bus(PPU ppu) {
        this.ppu = ppu;
    }

    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    public void setControllers(Controller controller1, Controller controller2) {
        this.controller1 = controller1;
        this.controller2 = controller2;
    }

    // -------------------------------------------------------------------------
    // CPU read / write
    // -------------------------------------------------------------------------

    /** Read one byte from the CPU address space. Returns value in [0, 255]. */
    public int read(int addr) {
        addr &= 0xFFFF;

        if (addr < 0x2000) {
            return ram[addr & 0x07FF] & 0xFF;
        }
        if (addr < 0x4000) {
            return ppu.readRegister(addr & 0x07);
        }
        if (addr == 0x4016) {
            return controller1 != null ? controller1.read() : 0;
        }
        if (addr == 0x4017) {
            return controller2 != null ? controller2.read() : 0;
        }
        if (addr >= 0x4020 && cartridge != null) {
            return cartridge.cpuRead(addr);
        }
        return 0;
    }

    /** Write one byte to the CPU address space. Data is masked to 8 bits. */
    public void write(int addr, int data) {
        addr &= 0xFFFF;
        data &= 0xFF;

        if (addr < 0x2000) {
            ram[addr & 0x07FF] = (byte) data;
            return;
        }
        if (addr < 0x4000) {
            ppu.writeRegister(addr & 0x07, data);
            return;
        }
        if (addr == 0x4014) {
            // OAM DMA: copy 256 bytes from CPU page into PPU OAM
            byte[] page = new byte[256];
            int base = data << 8;
            for (int i = 0; i < 256; i++) page[i] = (byte) read(base + i);
            ppu.writeDma(page);
            return;
        }
        if (addr == 0x4016) {
            if (controller1 != null) controller1.write(data);
            if (controller2 != null) controller2.write(data);
            return;
        }
        if (addr >= 0x4020 && cartridge != null) {
            cartridge.cpuWrite(addr, data);
        }
    }

    /** Read a 16-bit little-endian word. */
    public int readWord(int addr) {
        return read(addr) | (read(addr + 1) << 8);
    }
}
