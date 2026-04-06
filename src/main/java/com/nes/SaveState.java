package com.nes;

import com.nes.memory.mapper.Mapper;
import java.io.Serializable;

/**
 * A complete snapshot of the NES emulator state at a single point in time.
 *
 * Instances are created by {@link NES#captureState()} and consumed by
 * {@link NES#restoreState(SaveState)}.  Use {@link SaveStateManager} for
 * file I/O.
 *
 * All inner classes are immutable value objects and implement Serializable so
 * the whole tree can be written to disk with {@link java.io.ObjectOutputStream}.
 */
public final class SaveState implements Serializable {

    private static final long serialVersionUID = 1L;

    public final CpuState         cpu;
    public final BusState         bus;
    public final PpuState         ppu;
    public final ApuState         apu;
    public final Mapper.MapperState mapper;
    public final long             masterClock;
    public final long             frameCount;

    public SaveState(CpuState cpu, BusState bus, PpuState ppu, ApuState apu,
                     Mapper.MapperState mapper, long masterClock, long frameCount) {
        this.cpu         = cpu;
        this.bus         = bus;
        this.ppu         = ppu;
        this.apu         = apu;
        this.mapper      = mapper;
        this.masterClock = masterClock;
        this.frameCount  = frameCount;
    }

    // =========================================================================
    // CPU
    // =========================================================================

    public static final class CpuState implements Serializable {
        private static final long serialVersionUID = 1L;

        public final int     a, x, y, sp, pc, p, cycles;
        public final boolean pageCrossed;

        public CpuState(int a, int x, int y, int sp, int pc, int p,
                        int cycles, boolean pageCrossed) {
            this.a = a; this.x = x; this.y = y; this.sp = sp;
            this.pc = pc; this.p = p; this.cycles = cycles;
            this.pageCrossed = pageCrossed;
        }
    }

    // =========================================================================
    // Bus (2 KB internal RAM)
    // =========================================================================

    public static final class BusState implements Serializable {
        private static final long serialVersionUID = 1L;

        public final byte[] ram;   // 2048 bytes

        public BusState(byte[] ram) { this.ram = ram; }
    }

    // =========================================================================
    // PPU (includes nametable VRAM and palette RAM)
    // =========================================================================

    public static final class PpuState implements Serializable {
        private static final long serialVersionUID = 1L;

        // Scanline position
        public final int scanline, cycle;

        // Loopy internal registers
        public final int v, t, fineX;
        public final boolean w;

        // CPU-visible registers
        public final int colorTest, ctrl, mask, status, oamAddr, dataBuffer;

        // Background pipeline shift registers
        public final int bgShiftPatLo, bgShiftPatHi, bgShiftAttrLo, bgShiftAttrHi;

        // Background next-tile latches
        public final int bgNextTileId, bgNextTileAttr, bgNextTileLo, bgNextTileHi;

        // Primary OAM (64 sprites × 4 bytes)
        public final byte[] oam;

        // Secondary OAM (up to 8 selected sprites)
        public final int[] sprY, sprTile, sprAttr, sprX, sprPatLo, sprPatHi;
        public final int     sprCount;
        public final boolean sprite0Loaded, frameComplete;

        // PPUBus contents
        public final int[][] nametableBanks;  // [2][0x400] – 2 KB VRAM
        public final int[]   paletteRam;      // [32]

        public PpuState(int scanline, int cycle,
                        int v, int t, int fineX, boolean w,
                        int colorTest, int ctrl, int mask, int status,
                        int oamAddr, int dataBuffer,
                        int bgShiftPatLo, int bgShiftPatHi,
                        int bgShiftAttrLo, int bgShiftAttrHi,
                        int bgNextTileId, int bgNextTileAttr,
                        int bgNextTileLo, int bgNextTileHi,
                        byte[] oam,
                        int[] sprY, int[] sprTile, int[] sprAttr, int[] sprX,
                        int[] sprPatLo, int[] sprPatHi,
                        int sprCount, boolean sprite0Loaded, boolean frameComplete,
                        int[][] nametableBanks, int[] paletteRam) {
            this.scanline = scanline; this.cycle = cycle;
            this.v = v; this.t = t; this.fineX = fineX; this.w = w;
            this.colorTest = colorTest; this.ctrl = ctrl; this.mask = mask;
            this.status = status; this.oamAddr = oamAddr; this.dataBuffer = dataBuffer;
            this.bgShiftPatLo  = bgShiftPatLo;  this.bgShiftPatHi  = bgShiftPatHi;
            this.bgShiftAttrLo = bgShiftAttrLo; this.bgShiftAttrHi = bgShiftAttrHi;
            this.bgNextTileId  = bgNextTileId;  this.bgNextTileAttr = bgNextTileAttr;
            this.bgNextTileLo  = bgNextTileLo;  this.bgNextTileHi   = bgNextTileHi;
            this.oam = oam;
            this.sprY = sprY; this.sprTile = sprTile; this.sprAttr = sprAttr; this.sprX = sprX;
            this.sprPatLo = sprPatLo; this.sprPatHi = sprPatHi;
            this.sprCount = sprCount;
            this.sprite0Loaded = sprite0Loaded; this.frameComplete = frameComplete;
            this.nametableBanks = nametableBanks; this.paletteRam = paletteRam;
        }
    }

    // =========================================================================
    // APU
    // =========================================================================

    public static final class ApuState implements Serializable {
        private static final long serialVersionUID = 1L;

        // ── Pulse 1 & 2 ───────────────────────────────────────────────────────
        public final boolean[] pulseEnabled, pulseEnvLoop, pulseEnvConst, pulseLenHalt;
        public final int[]     pulseLenCtr, pulseDuty, pulseSeqPos, pulseTimer, pulsePeriod;
        public final int[]     pulseEnvelope, pulseEnvPeriod, pulseEnvTimer;
        // Sweep
        public final boolean[] sweepEnabled, sweepNegate, sweepReload;
        public final int[]     sweepPeriod, sweepShift, sweepTimer;

        // ── Triangle ──────────────────────────────────────────────────────────
        public final boolean   triEnabled, triLinearReloadFlag, triLenHalt;
        public final int       triLenCtr, triLinearCtr, triLinearReload;
        public final int       triTimer, triPeriod, triSeqPos;

        // ── Noise ─────────────────────────────────────────────────────────────
        public final boolean   noiseEnabled, noiseLenHalt, noiseEnvLoop, noiseEnvConst, noiseMode;
        public final int       noiseLenCtr, noiseEnvelope, noiseEnvPeriod, noiseEnvTimer;
        public final int       noiseShiftReg, noiseTimer, noisePeriod;

        // ── DMC ───────────────────────────────────────────────────────────────
        public final boolean   dmcIrqEnabled, dmcLoop, dmcSilence, dmcIrq;
        public final int       dmcPeriod, dmcTimer, dmcOutputLevel;
        public final int       dmcSampleAddr, dmcSampleLength;
        public final int       dmcCurrentAddr, dmcBytesRemaining;
        public final int       dmcSampleBuffer, dmcShiftReg, dmcBitsRemaining;

        // ── Frame counter ─────────────────────────────────────────────────────
        public final int       frameMode, frameCycles;
        public final boolean   frameIrqInhibit, frameIrq;

        // ── Misc ──────────────────────────────────────────────────────────────
        public final boolean   apuCycle;

        public ApuState(
                boolean[] pulseEnabled, boolean[] pulseEnvLoop,
                boolean[] pulseEnvConst, boolean[] pulseLenHalt,
                int[] pulseLenCtr, int[] pulseDuty, int[] pulseSeqPos,
                int[] pulseTimer, int[] pulsePeriod,
                int[] pulseEnvelope, int[] pulseEnvPeriod, int[] pulseEnvTimer,
                boolean[] sweepEnabled, boolean[] sweepNegate, boolean[] sweepReload,
                int[] sweepPeriod, int[] sweepShift, int[] sweepTimer,
                boolean triEnabled, boolean triLinearReloadFlag, boolean triLenHalt,
                int triLenCtr, int triLinearCtr, int triLinearReload,
                int triTimer, int triPeriod, int triSeqPos,
                boolean noiseEnabled, boolean noiseLenHalt,
                boolean noiseEnvLoop, boolean noiseEnvConst, boolean noiseMode,
                int noiseLenCtr, int noiseEnvelope, int noiseEnvPeriod, int noiseEnvTimer,
                int noiseShiftReg, int noiseTimer, int noisePeriod,
                boolean dmcIrqEnabled, boolean dmcLoop, boolean dmcSilence, boolean dmcIrq,
                int dmcPeriod, int dmcTimer, int dmcOutputLevel,
                int dmcSampleAddr, int dmcSampleLength,
                int dmcCurrentAddr, int dmcBytesRemaining,
                int dmcSampleBuffer, int dmcShiftReg, int dmcBitsRemaining,
                int frameMode, int frameCycles, boolean frameIrqInhibit, boolean frameIrq,
                boolean apuCycle) {
            this.pulseEnabled = pulseEnabled; this.pulseEnvLoop = pulseEnvLoop;
            this.pulseEnvConst = pulseEnvConst; this.pulseLenHalt = pulseLenHalt;
            this.pulseLenCtr = pulseLenCtr; this.pulseDuty = pulseDuty;
            this.pulseSeqPos = pulseSeqPos; this.pulseTimer = pulseTimer;
            this.pulsePeriod = pulsePeriod;
            this.pulseEnvelope = pulseEnvelope; this.pulseEnvPeriod = pulseEnvPeriod;
            this.pulseEnvTimer = pulseEnvTimer;
            this.sweepEnabled = sweepEnabled; this.sweepNegate = sweepNegate;
            this.sweepReload = sweepReload;
            this.sweepPeriod = sweepPeriod; this.sweepShift = sweepShift; this.sweepTimer = sweepTimer;
            this.triEnabled = triEnabled; this.triLinearReloadFlag = triLinearReloadFlag;
            this.triLenHalt = triLenHalt;
            this.triLenCtr = triLenCtr; this.triLinearCtr = triLinearCtr;
            this.triLinearReload = triLinearReload;
            this.triTimer = triTimer; this.triPeriod = triPeriod; this.triSeqPos = triSeqPos;
            this.noiseEnabled = noiseEnabled; this.noiseLenHalt = noiseLenHalt;
            this.noiseEnvLoop = noiseEnvLoop; this.noiseEnvConst = noiseEnvConst;
            this.noiseMode = noiseMode;
            this.noiseLenCtr = noiseLenCtr; this.noiseEnvelope = noiseEnvelope;
            this.noiseEnvPeriod = noiseEnvPeriod; this.noiseEnvTimer = noiseEnvTimer;
            this.noiseShiftReg = noiseShiftReg; this.noiseTimer = noiseTimer;
            this.noisePeriod = noisePeriod;
            this.dmcIrqEnabled = dmcIrqEnabled; this.dmcLoop = dmcLoop;
            this.dmcSilence = dmcSilence; this.dmcIrq = dmcIrq;
            this.dmcPeriod = dmcPeriod; this.dmcTimer = dmcTimer;
            this.dmcOutputLevel = dmcOutputLevel;
            this.dmcSampleAddr = dmcSampleAddr; this.dmcSampleLength = dmcSampleLength;
            this.dmcCurrentAddr = dmcCurrentAddr; this.dmcBytesRemaining = dmcBytesRemaining;
            this.dmcSampleBuffer = dmcSampleBuffer; this.dmcShiftReg = dmcShiftReg;
            this.dmcBitsRemaining = dmcBitsRemaining;
            this.frameMode = frameMode; this.frameCycles = frameCycles;
            this.frameIrqInhibit = frameIrqInhibit; this.frameIrq = frameIrq;
            this.apuCycle = apuCycle;
        }
    }
}
