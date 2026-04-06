package com.nes.memory.mapper;

import com.nes.memory.Cartridge.MirrorMode;

/**
 * Mapper 4 — MMC3 (TxROM).
 *
 * The most common advanced NES mapper (~24% of the library). Features
 * flexible PRG/CHR bank switching and a scanline-based IRQ counter.
 *
 * CPU memory map:
 *   $6000–$7FFF  PRG-RAM (8 KB, optionally battery-backed and write-protected)
 *   $8000–$9FFF  PRG bank A (swappable or fixed second-to-last, per PRG mode)
 *   $A000–$BFFF  PRG bank B (R7, always swappable)
 *   $C000–$DFFF  PRG bank C (fixed second-to-last or swappable, per PRG mode)
 *   $E000–$FFFF  PRG bank D (always fixed to last 8 KB bank)
 *
 * PPU memory map ($0000–$1FFF, 8 KB total, six independently switchable banks):
 *   CHR mode 0 (bit 7 of $8000 = 0):
 *     $0000–$07FF  R0 (2 KB)
 *     $0800–$0FFF  R1 (2 KB)
 *     $1000–$13FF  R2 (1 KB)
 *     $1400–$17FF  R3 (1 KB)
 *     $1800–$1BFF  R4 (1 KB)
 *     $1C00–$1FFF  R5 (1 KB)
 *   CHR mode 1 (bit 7 = 1): layout above is swapped — 2 KB banks in upper half,
 *     1 KB banks in lower half.
 *
 * Registers (write to even/odd pairs):
 *   $8000 — Bank Select : bits 2-0 = target register (R0–R7);
 *                          bit 6 = PRG mode; bit 7 = CHR inversion
 *   $8001 — Bank Data   : value written to the register chosen above
 *   $A000 — Mirroring   : bit 0 = 0 vertical / 1 horizontal
 *   $A001 — PRG-RAM protect (bits 7-6; bit 7 = protect, bit 6 = enable)
 *   $C000 — IRQ Latch   : reload value for the IRQ counter
 *   $C001 — IRQ Reload  : zero the counter and set the reload flag
 *   $E000 — IRQ Disable : disable and acknowledge IRQ
 *   $E001 — IRQ Enable  : enable IRQ
 *
 * IRQ mechanism:
 *   The counter decrements once per scanline (approximation of PPU A12
 *   rising edges). When it reaches zero and IRQ is enabled, an IRQ fires.
 *
 * Games: Super Mario Bros. 2/3, Kirby's Adventure, Mega Man 3–6,
 *        Contra Force, Teenage Mutant Ninja Turtles.
 */
public class MapperMMC3 implements Mapper {

    // ROM / RAM
    private final byte[] prgRom;
    private final byte[] chrMem;      // CHR-ROM or CHR-RAM
    private final boolean hasChrRam;
    private final byte[] prgRam;

    private final int prgBank8Count;  // number of 8 KB PRG banks
    private final int chrBank1Count;  // number of 1 KB CHR banks

    // Bank registers R0–R7
    private final int[] reg = new int[8];

    // $8000 bank-select register
    private int bankSelect = 0;       // bits 2-0 = target, bit 6 = PRG mode, bit 7 = CHR invert

    // $A000 mirror
    private MirrorMode mirrorMode = MirrorMode.VERTICAL;

    // $A001 PRG-RAM protect
    private boolean prgRamEnabled    = true;
    private boolean prgRamWriteProtect = false;

    // IRQ state
    private int  irqLatch   = 0;
    private int  irqCounter = 0;
    private boolean irqReload  = false;
    private boolean irqEnabled = false;
    private boolean irqPending = false;

    public MapperMMC3(byte[] prgRom, byte[] chrRom, boolean battery) {
        this.prgRom       = prgRom;
        this.hasChrRam    = (chrRom == null);
        this.chrMem       = hasChrRam ? new byte[0x2000] : chrRom;
        this.prgRam       = new byte[0x2000];
        this.prgBank8Count = prgRom.length / 0x2000;
        this.chrBank1Count = Math.max(1, chrMem.length / 0x400);
    }

    // =========================================================================
    // CPU side
    // =========================================================================

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x6000 && addr <= 0x7FFF) {
            return prgRamEnabled ? prgRam[addr & 0x1FFF] & 0xFF : 0;
        }
        if (addr >= 0x8000) {
            return prgRom[prgOffset(addr)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int data) {
        if (addr >= 0x6000 && addr <= 0x7FFF) {
            if (prgRamEnabled && !prgRamWriteProtect) {
                prgRam[addr & 0x1FFF] = (byte) data;
            }
            return;
        }
        if (addr < 0x8000) return;

        boolean even = (addr & 1) == 0;

        if (addr <= 0x9FFF) {
            if (even) {
                bankSelect = data;
            } else {
                int r = bankSelect & 0x07;
                // R0 and R1 are 2 KB banks — ignore the lowest address bit
                if (r == 0 || r == 1) data &= 0xFE;
                reg[r] = data;
            }
        } else if (addr <= 0xBFFF) {
            if (even) {
                mirrorMode = (data & 1) == 0 ? MirrorMode.VERTICAL : MirrorMode.HORIZONTAL;
            } else {
                prgRamWriteProtect = (data & 0x40) != 0;
                prgRamEnabled      = (data & 0x80) != 0;
            }
        } else if (addr <= 0xDFFF) {
            if (even) {
                irqLatch = data;
            } else {
                irqCounter = 0;
                irqReload  = true;
            }
        } else { // $E000–$FFFF
            if (even) {
                irqEnabled = false;
                irqPending = false;
            } else {
                irqEnabled = true;
            }
        }
    }

    // =========================================================================
    // PPU side
    // =========================================================================

    @Override
    public int ppuRead(int addr) {
        if (addr < 0x2000) {
            return chrMem[chrOffset(addr)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int addr, int data) {
        if (addr < 0x2000 && hasChrRam) {
            chrMem[chrOffset(addr)] = (byte) data;
        }
    }

    // =========================================================================
    // Scanline IRQ
    // =========================================================================

    @Override
    public void tickScanline() {
        if (irqCounter == 0 || irqReload) {
            irqCounter = irqLatch;
            irqReload  = false;
        } else {
            irqCounter--;
        }
        if (irqCounter == 0 && irqEnabled) {
            irqPending = true;
        }
    }

    @Override
    public boolean irqPending() {
        return irqPending;
    }

    // =========================================================================
    // Mirror mode (readable by Cartridge)
    // =========================================================================

    public MirrorMode getMirrorMode() {
        return mirrorMode;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Translate a CPU address $8000–$FFFF to a byte offset in prgRom.
     *
     * The 32 KB window is divided into four 8 KB slots:
     *   Slot 0 ($8000–$9FFF): R6 in PRG mode 0, second-to-last in PRG mode 1
     *   Slot 1 ($A000–$BFFF): R7 (always swappable)
     *   Slot 2 ($C000–$DFFF): second-to-last in PRG mode 0, R6 in PRG mode 1
     *   Slot 3 ($E000–$FFFF): always last bank
     */
    private int prgOffset(int addr) {
        int slot = (addr >> 13) & 0x03; // 0–3 based on $8000/$A000/$C000/$E000
        boolean prgMode = (bankSelect & 0x40) != 0;

        int bank;
        switch (slot) {
            case 0: bank = prgMode ? (prgBank8Count - 2) : (reg[6] & 0x3F); break;
            case 1: bank = reg[7] & 0x3F;                                     break;
            case 2: bank = prgMode ? (reg[6] & 0x3F) : (prgBank8Count - 2);  break;
            default: bank = prgBank8Count - 1;                                break;
        }

        return (bank % prgBank8Count) * 0x2000 + (addr & 0x1FFF);
    }

    /**
     * Translate a PPU address $0000–$1FFF to a byte offset in chrMem.
     *
     * CHR inversion (bit 7 of bankSelect) swaps the two halves of the 8 KB
     * window so the 2 KB banks appear at $1000 and the 1 KB banks at $0000.
     */
    private int chrOffset(int addr) {
        boolean chrInvert = (bankSelect & 0x80) != 0;
        if (chrInvert) addr ^= 0x1000;   // swap halves

        int bank;
        if (addr < 0x0800) {
            bank = reg[0] & 0xFE;         // 2 KB aligned: R0, covers $0000–$07FF
            return (bank % chrBank1Count) * 0x400 + (addr & 0x7FF);
        } else if (addr < 0x1000) {
            bank = reg[1] & 0xFE;         // 2 KB aligned: R1, covers $0800–$0FFF
            return (bank % chrBank1Count) * 0x400 + (addr & 0x7FF);
        } else if (addr < 0x1400) {
            bank = reg[2];                // 1 KB: R2
        } else if (addr < 0x1800) {
            bank = reg[3];                // 1 KB: R3
        } else if (addr < 0x1C00) {
            bank = reg[4];                // 1 KB: R4
        } else {
            bank = reg[5];                // 1 KB: R5
        }

        return (bank % chrBank1Count) * 0x400 + (addr & 0x3FF);
    }
}
