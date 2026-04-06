package com.nes.memory.mapper;

import com.nes.memory.Cartridge.MirrorMode;

/**
 * Mapper 1 — MMC1 (SxROM).
 *
 * Used by ~30% of the NES library including The Legend of Zelda, Metroid,
 * Mega Man 2, and Castlevania II.
 *
 * CPU memory map:
 *   $6000–$7FFF  PRG-RAM (8 KB, battery-backed when present)
 *   $8000–$FFFF  PRG-ROM, bank-switched according to Control register
 *
 * PPU memory map:
 *   $0000–$0FFF  CHR bank 0 (4 KB)
 *   $1000–$1FFF  CHR bank 1 (4 KB) — same as bank 0 in 8 KB mode
 *
 * ---- Serial shift register ------------------------------------------------
 * Every write to $8000–$FFFF feeds bit 0 into a 5-bit shift register.
 * Writing with bit 7 set resets the register immediately.
 * After 5 serial writes the accumulated value is loaded into one of four
 * internal registers selected by bits 14–13 of the write address:
 *
 *   $8000–$9FFF  → Control  (bits 14-13 = 00)
 *   $A000–$BFFF  → CHR Bank 0
 *   $C000–$DFFF  → CHR Bank 1
 *   $E000–$FFFF  → PRG Bank
 *
 * ---- Control register (bits) ----------------------------------------------
 *   1-0  Mirror: 0=single-lo, 1=single-hi, 2=vertical, 3=horizontal
 *   3-2  PRG mode:
 *          0,1 = switch 32 KB at $8000 (low bit of PRG bank ignored)
 *          2   = fix first bank at $8000, switch 16 KB at $C000
 *          3   = switch 16 KB at $8000, fix last bank at $C000  ← Zelda default
 *   4    CHR mode: 0 = switch 8 KB, 1 = switch two 4 KB banks
 *
 * ---- PRG Bank register (bits) ---------------------------------------------
 *   3-0  PRG bank number (16 KB bank for modes 2/3; 32 KB for modes 0/1)
 *   4    PRG-RAM disable (MMC1A only; ignored here)
 */
public class MapperMMC1 implements Mapper {

    // ROM data
    private final byte[] prgRom;
    private final byte[] chrMem;       // CHR-ROM or CHR-RAM
    private final boolean hasChrRam;
    private final byte[] prgRam;       // 8 KB PRG-RAM

    // Serial shift register
    private int  shiftReg   = 0x10;   // 5-bit; bit 4 is the sentinel
    private int  writeCount = 0;

    // Internal MMC1 registers
    private int regControl  = 0x0C;   // default: fix-last PRG mode, CHR 8 KB
    private int regChrBank0 = 0;
    private int regChrBank1 = 0;
    private int regPrgBank  = 0;

    // Derived mirror mode (updated whenever regControl changes)
    private MirrorMode mirrorMode = MirrorMode.HORIZONTAL;

    // Number of 16 KB PRG banks
    private final int prgBankCount;

    /**
     * @param prgRom  full PRG-ROM (N × 16 KB)
     * @param chrRom  CHR-ROM data, or {@code null} to allocate 8 KB CHR-RAM
     * @param battery true if the cartridge has battery-backed PRG-RAM
     */
    public MapperMMC1(byte[] prgRom, byte[] chrRom, boolean battery) {
        this.prgRom      = prgRom;
        this.hasChrRam   = (chrRom == null);
        this.chrMem      = hasChrRam ? new byte[0x2000] : chrRom;
        this.prgRam      = new byte[0x2000];   // always present; battery flag affects save, not emulation
        this.prgBankCount = prgRom.length / 0x4000;

        updateMirrorMode();
    }

    // =========================================================================
    // CPU side
    // =========================================================================

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x6000 && addr <= 0x7FFF) {
            return prgRam[addr & 0x1FFF] & 0xFF;
        }
        if (addr >= 0x8000) {
            return prgRom[prgOffset(addr)] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int data) {
        if (addr >= 0x6000 && addr <= 0x7FFF) {
            prgRam[addr & 0x1FFF] = (byte) data;
            return;
        }
        if (addr < 0x8000) return;

        // Reset signal: bit 7 set
        if ((data & 0x80) != 0) {
            shiftReg   = 0x10;
            writeCount = 0;
            // Reset also forces PRG mode 3 (fix-last) per MMC1 spec
            regControl |= 0x0C;
            updateMirrorMode();
            return;
        }

        // Shift bit 0 into the register (LSB-first)
        shiftReg = ((data & 1) << 4) | (shiftReg >> 1);
        writeCount++;

        if (writeCount == 5) {
            int value = shiftReg & 0x1F;
            // Target register selected by bits 14-13 of the address
            switch ((addr >> 13) & 0x03) {
                case 0: regControl  = value; updateMirrorMode(); break;
                case 1: regChrBank0 = value; break;
                case 2: regChrBank1 = value; break;
                case 3: regPrgBank  = value; break;
            }
            // Reset shift register (sentinel bit back)
            shiftReg   = 0x10;
            writeCount = 0;
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
    // Mirror mode (readable by Cartridge)
    // =========================================================================

    public MirrorMode getMirrorMode() {
        return mirrorMode;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Translate a CPU address in $8000–$FFFF to a byte offset into prgRom.
     *
     * PRG mode (bits 3-2 of Control):
     *   0,1 → 32 KB mode: both $8000 and $C000 windows come from the same
     *         32 KB block selected by regPrgBank[3:1].
     *   2   → fix first bank (bank 0) at $8000; switch at $C000.
     *   3   → switch at $8000; fix last bank at $C000.
     */
    private int prgOffset(int addr) {
        int prgMode = (regControl >> 2) & 0x03;
        int bank;

        if (prgMode <= 1) {
            // 32 KB mode: ignore low bit of regPrgBank
            int base = (regPrgBank & 0x1E) * 0x4000;
            bank = base + (addr & 0x7FFF);
        } else if (prgMode == 2) {
            // Fix first bank at $8000; switch at $C000
            if (addr < 0xC000) {
                bank = (addr & 0x3FFF);                               // bank 0
            } else {
                bank = (regPrgBank & 0x0F) * 0x4000 + (addr & 0x3FFF); // switched
            }
        } else {
            // Fix last bank at $C000; switch at $8000
            if (addr >= 0xC000) {
                bank = (prgBankCount - 1) * 0x4000 + (addr & 0x3FFF); // last bank
            } else {
                bank = (regPrgBank & 0x0F) * 0x4000 + (addr & 0x3FFF); // switched
            }
        }

        return bank % prgRom.length;
    }

    /**
     * Translate a PPU address in $0000–$1FFF to a byte offset into chrMem.
     *
     * CHR mode (bit 4 of Control):
     *   0 → 8 KB mode: regChrBank0[4:1] selects an 8 KB block.
     *   1 → 4 KB mode: regChrBank0 for $0000–$0FFF, regChrBank1 for $1000–$1FFF.
     */
    private int chrOffset(int addr) {
        boolean chr4k = (regControl & 0x10) != 0;
        int offset;

        if (!chr4k) {
            // 8 KB mode: low bit of regChrBank0 is ignored
            offset = (regChrBank0 & 0x1E) * 0x1000 + (addr & 0x1FFF);
        } else {
            if (addr < 0x1000) {
                offset = regChrBank0 * 0x1000 + (addr & 0x0FFF);
            } else {
                offset = regChrBank1 * 0x1000 + (addr & 0x0FFF);
            }
        }

        return offset % chrMem.length;
    }

    private void updateMirrorMode() {
        switch (regControl & 0x03) {
            case 0: mirrorMode = MirrorMode.SINGLE_SCREEN_LO; break;
            case 1: mirrorMode = MirrorMode.SINGLE_SCREEN_HI; break;
            case 2: mirrorMode = MirrorMode.VERTICAL;         break;
            case 3: mirrorMode = MirrorMode.HORIZONTAL;       break;
        }
    }
}
