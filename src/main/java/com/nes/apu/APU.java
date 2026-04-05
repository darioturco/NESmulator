package com.nes.apu;

/**
 * Ricoh 2A03 Audio Processing Unit — NTSC variant.
 *
 * Implements four channels:
 *   Pulse 1 & 2  ($4000–$4007) — square waves with envelope, sweep, length counter
 *   Triangle     ($4008–$400B) — triangle wave with linear + length counter
 *   Noise        ($400C–$400F) — LFSR noise with envelope + length counter
 *   DMC          ($4010–$4013) — stubbed (output = 0; avoids length-counter muting)
 *
 * Audio is mixed using the NES non-linear mixing formula and streamed to
 * {@link AudioOutput} at {@link #SAMPLE_RATE} Hz.
 */
public class APU {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final int SAMPLE_RATE = 44100;
    private static final double CPU_FREQ = 1_789_773.0;

    private static final int[] LENGTH_TABLE = {
        10, 254, 20,  2, 40,  4, 80,  6, 160,  8, 60, 10, 14, 12, 26, 14,
        12,  16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
    };

    private static final int[][] DUTY_TABLE = {
        {0, 1, 0, 0, 0, 0, 0, 0},   // 12.5 %
        {0, 1, 1, 0, 0, 0, 0, 0},   // 25 %
        {0, 1, 1, 1, 1, 0, 0, 0},   // 50 %
        {1, 0, 0, 1, 1, 1, 1, 1}    // 75 % (inverted 25 %)
    };

    private static final int[] NOISE_PERIOD_TABLE = {
        4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
    };

    private static final int[] TRI_TABLE = {
        15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
         0,  1,  2,  3,  4,  5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    // ── Pulse channels (index 0 = Pulse 1, index 1 = Pulse 2) ────────────────

    private final boolean[] pulseEnabled   = new boolean[2];
    private final int[]     pulseLenCtr    = new int[2];
    private final int[]     pulseDuty      = new int[2];
    private final int[]     pulseSeqPos    = new int[2];
    private final int[]     pulseTimer     = new int[2];
    private final int[]     pulsePeriod    = new int[2];
    private final int[]     pulseEnvelope  = new int[2];
    private final int[]     pulseEnvPeriod = new int[2];
    private final int[]     pulseEnvTimer  = new int[2];
    private final boolean[] pulseEnvLoop   = new boolean[2];
    private final boolean[] pulseEnvConst  = new boolean[2];
    private final boolean[] pulseLenHalt   = new boolean[2];

    // Sweep unit
    private final boolean[] sweepEnabled = new boolean[2];
    private final int[]     sweepPeriod  = new int[2];
    private final boolean[] sweepNegate  = new boolean[2];
    private final int[]     sweepShift   = new int[2];
    private final int[]     sweepTimer   = new int[2];
    private final boolean[] sweepReload  = new boolean[2];

    // ── Triangle channel ──────────────────────────────────────────────────────

    private boolean triEnabled;
    private int     triLenCtr;
    private int     triLinearCtr;
    private int     triLinearReload;
    private boolean triLinearReloadFlag;
    private boolean triLenHalt;
    private int     triTimer;
    private int     triPeriod;
    private int     triSeqPos;

    // ── Noise channel ─────────────────────────────────────────────────────────

    private boolean noiseEnabled;
    private int     noiseLenCtr;
    private boolean noiseLenHalt;
    private int     noiseEnvelope;
    private int     noiseEnvPeriod;
    private int     noiseEnvTimer;
    private boolean noiseEnvLoop;
    private boolean noiseEnvConst;
    private int     noiseShiftReg = 1;
    private boolean noiseMode;
    private int     noiseTimer;
    private int     noisePeriod;

    // ── Frame counter ─────────────────────────────────────────────────────────

    private int     frameMode;       // 0 = 4-step, 1 = 5-step
    private boolean frameIrqInhibit;
    private int     frameCycles;
    private boolean frameIrq;

    // ── APU half-cycle flag (pulse timers tick every other CPU cycle) ─────────

    private boolean apuCycle = false;

    // ── Audio output ──────────────────────────────────────────────────────────

    private final AudioOutput audioOutput = new AudioOutput(SAMPLE_RATE);
    private double sampleAccum = 0.0;
    private volatile boolean muted = true;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Open the audio output line. Call once before the game loop starts. */
    public void start() {
        audioOutput.start();
    }

    /** Reset all channels to their power-up state. */
    public void reset() {
        for (int p = 0; p < 2; p++) {
            pulseEnabled[p]   = false;
            pulseLenCtr[p]    = 0;
            pulseSeqPos[p]    = 0;
            pulseTimer[p]     = 0;
            pulsePeriod[p]    = 0;
            pulseEnvelope[p]  = 0;
            pulseEnvTimer[p]  = 0;
            sweepTimer[p]     = 0;
            sweepReload[p]    = false;
        }
        triEnabled          = false;
        triLenCtr           = 0;
        triLinearCtr        = 0;
        triLinearReloadFlag = false;
        triTimer            = 0;
        triSeqPos           = 0;
        noiseEnabled        = false;
        noiseLenCtr         = 0;
        noiseShiftReg       = 1;
        noiseTimer          = 0;
        frameCycles         = 0;
        frameIrq            = false;
        apuCycle            = false;
        sampleAccum         = 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Register writes  ($4000–$4017)
    // ─────────────────────────────────────────────────────────────────────────

    public void write(int addr, int val) {
        val &= 0xFF;
        switch (addr) {

            // ── Pulse 1 ──────────────────────────────────────────────────────
            case 0x4000: writePulseCtrl(0, val);    break;
            case 0x4001: writePulseSweep(0, val);   break;
            case 0x4002: writePulseTimerLo(0, val); break;
            case 0x4003: writePulseTimerHi(0, val); break;

            // ── Pulse 2 ──────────────────────────────────────────────────────
            case 0x4004: writePulseCtrl(1, val);    break;
            case 0x4005: writePulseSweep(1, val);   break;
            case 0x4006: writePulseTimerLo(1, val); break;
            case 0x4007: writePulseTimerHi(1, val); break;

            // ── Triangle ─────────────────────────────────────────────────────
            case 0x4008:
                triLenHalt          = (val & 0x80) != 0;
                triLinearReload     = val & 0x7F;
                break;
            case 0x400A:
                triPeriod = (triPeriod & 0x700) | val;
                break;
            case 0x400B:
                triPeriod = (triPeriod & 0x0FF) | ((val & 0x07) << 8);
                if (triEnabled) triLenCtr = LENGTH_TABLE[(val >> 3) & 0x1F];
                triLinearReloadFlag = true;
                break;

            // ── Noise ────────────────────────────────────────────────────────
            case 0x400C:
                noiseLenHalt   = (val & 0x20) != 0;
                noiseEnvConst  = (val & 0x10) != 0;
                noiseEnvPeriod = val & 0x0F;
                noiseEnvLoop   = (val & 0x20) != 0;
                break;
            case 0x400E:
                noiseMode   = (val & 0x80) != 0;
                noisePeriod = NOISE_PERIOD_TABLE[val & 0x0F];
                break;
            case 0x400F:
                if (noiseEnabled) noiseLenCtr = LENGTH_TABLE[(val >> 3) & 0x1F];
                noiseEnvTimer = noiseEnvPeriod;
                noiseEnvelope = 15;
                break;

            // ── DMC ($4010–$4013): stub — channel output stays at 0 ──────────
            case 0x4010: case 0x4011: case 0x4012: case 0x4013: break;

            // ── Status ($4015) ────────────────────────────────────────────────
            case 0x4015:
                pulseEnabled[0] = (val & 0x01) != 0;
                pulseEnabled[1] = (val & 0x02) != 0;
                triEnabled      = (val & 0x04) != 0;
                noiseEnabled    = (val & 0x08) != 0;
                if (!pulseEnabled[0]) pulseLenCtr[0] = 0;
                if (!pulseEnabled[1]) pulseLenCtr[1] = 0;
                if (!triEnabled)      triLenCtr       = 0;
                if (!noiseEnabled)    noiseLenCtr     = 0;
                frameIrq = false;
                break;

            // ── Frame counter ($4017) ─────────────────────────────────────────
            case 0x4017:
                frameMode       = (val & 0x80) != 0 ? 1 : 0;
                frameIrqInhibit = (val & 0x40) != 0;
                frameCycles     = 0;
                if (frameIrqInhibit) frameIrq = false;
                if (frameMode == 1) {
                    clockEnvelopes();
                    clockLengthAndSweep();
                }
                break;
        }
    }

    public int readStatus() {
        int s = 0;
        if (pulseLenCtr[0] > 0) s |= 0x01;
        if (pulseLenCtr[1] > 0) s |= 0x02;
        if (triLenCtr      > 0) s |= 0x04;
        if (noiseLenCtr    > 0) s |= 0x08;
        if (frameIrq)           s |= 0x40;
        frameIrq = false;
        return s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pulse helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void writePulseCtrl(int p, int val) {
        pulseDuty[p]      = (val >> 6) & 0x03;
        pulseLenHalt[p]   = (val & 0x20) != 0;
        pulseEnvConst[p]  = (val & 0x10) != 0;
        pulseEnvPeriod[p] = val & 0x0F;
        pulseEnvLoop[p]   = (val & 0x20) != 0;
    }

    private void writePulseSweep(int p, int val) {
        sweepEnabled[p] = (val & 0x80) != 0;
        sweepPeriod[p]  = (val >> 4) & 0x07;
        sweepNegate[p]  = (val & 0x08) != 0;
        sweepShift[p]   = val & 0x07;
        sweepReload[p]  = true;
    }

    private void writePulseTimerLo(int p, int val) {
        pulsePeriod[p] = (pulsePeriod[p] & 0x700) | val;
    }

    private void writePulseTimerHi(int p, int val) {
        pulsePeriod[p] = (pulsePeriod[p] & 0x0FF) | ((val & 0x07) << 8);
        if (pulseEnabled[p]) pulseLenCtr[p] = LENGTH_TABLE[(val >> 3) & 0x1F];
        pulseSeqPos[p]   = 0;
        pulseEnvTimer[p] = pulseEnvPeriod[p];
        pulseEnvelope[p] = 15;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tick  (called every CPU cycle)
    // ─────────────────────────────────────────────────────────────────────────

    public void tick() {
        // Pulse timers run at half the CPU rate (every other CPU cycle)
        apuCycle = !apuCycle;
        if (apuCycle) tickPulseTimers();

        tickTriangleTimer();
        tickNoiseTimer();
        tickFrameCounter();

        // Output one sample at SAMPLE_RATE Hz using an accumulator
        sampleAccum += SAMPLE_RATE;
        if (sampleAccum >= CPU_FREQ) {
            sampleAccum -= CPU_FREQ;
            audioOutput.write(muted ? 0.0f : mix());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame counter
    // ─────────────────────────────────────────────────────────────────────────

    private void tickFrameCounter() {
        frameCycles++;
        if (frameMode == 0) {
            // 4-step sequence
            switch (frameCycles) {
                case 7457:  clockEnvelopes(); break;
                case 14913: clockEnvelopes(); clockLengthAndSweep(); break;
                case 22371: clockEnvelopes(); break;
                case 29830:
                    clockEnvelopes();
                    clockLengthAndSweep();
                    if (!frameIrqInhibit) frameIrq = true;
                    frameCycles = 0;
                    break;
            }
        } else {
            // 5-step sequence (no IRQ)
            switch (frameCycles) {
                case 7457:  clockEnvelopes(); break;
                case 14913: clockEnvelopes(); clockLengthAndSweep(); break;
                case 22371: clockEnvelopes(); break;
                case 37282:
                    clockEnvelopes();
                    clockLengthAndSweep();
                    frameCycles = 0;
                    break;
            }
        }
    }

    private void clockEnvelopes() {
        // Pulse envelopes
        for (int p = 0; p < 2; p++) {
            if (pulseEnvTimer[p] > 0) {
                pulseEnvTimer[p]--;
            } else {
                pulseEnvTimer[p] = pulseEnvPeriod[p];
                if (pulseEnvelope[p] > 0) {
                    pulseEnvelope[p]--;
                } else if (pulseEnvLoop[p]) {
                    pulseEnvelope[p] = 15;
                }
            }
        }
        // Triangle linear counter
        if (triLinearReloadFlag) {
            triLinearCtr = triLinearReload;
        } else if (triLinearCtr > 0) {
            triLinearCtr--;
        }
        if (!triLenHalt) triLinearReloadFlag = false;
        // Noise envelope
        if (noiseEnvTimer > 0) {
            noiseEnvTimer--;
        } else {
            noiseEnvTimer = noiseEnvPeriod;
            if (noiseEnvelope > 0) {
                noiseEnvelope--;
            } else if (noiseEnvLoop) {
                noiseEnvelope = 15;
            }
        }
    }

    private void clockLengthAndSweep() {
        for (int p = 0; p < 2; p++) {
            if (!pulseLenHalt[p] && pulseLenCtr[p] > 0) pulseLenCtr[p]--;
            // Sweep unit
            if (sweepReload[p]) {
                sweepTimer[p] = sweepPeriod[p];
                sweepReload[p] = false;
            } else if (sweepTimer[p] > 0) {
                sweepTimer[p]--;
            } else {
                sweepTimer[p] = sweepPeriod[p];
                if (sweepEnabled[p] && sweepShift[p] > 0 && !isSweepMuting(p)) {
                    int change = pulsePeriod[p] >> sweepShift[p];
                    // Pulse 1 negate: one's complement; pulse 2: two's complement
                    pulsePeriod[p] += sweepNegate[p] ? -(change + (p == 0 ? 1 : 0)) : change;
                }
            }
        }
        if (!triLenHalt   && triLenCtr   > 0) triLenCtr--;
        if (!noiseLenHalt && noiseLenCtr > 0) noiseLenCtr--;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Channel timers
    // ─────────────────────────────────────────────────────────────────────────

    private void tickPulseTimers() {
        for (int p = 0; p < 2; p++) {
            if (pulseTimer[p] > 0) {
                pulseTimer[p]--;
            } else {
                pulseTimer[p]  = pulsePeriod[p];
                pulseSeqPos[p] = (pulseSeqPos[p] + 1) & 7;
            }
        }
    }

    private void tickTriangleTimer() {
        if (triLenCtr == 0 || triLinearCtr == 0) return;
        if (triTimer > 0) {
            triTimer--;
        } else {
            triTimer  = triPeriod;
            triSeqPos = (triSeqPos + 1) & 31;
        }
    }

    private void tickNoiseTimer() {
        if (noiseTimer > 0) {
            noiseTimer--;
        } else {
            noiseTimer = noisePeriod;
            int bit   = noiseMode ? 6 : 1;
            int feed  = ((noiseShiftReg ^ (noiseShiftReg >> bit)) & 1);
            noiseShiftReg = (noiseShiftReg >> 1) | (feed << 14);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Output
    // ─────────────────────────────────────────────────────────────────────────

    /** NES non-linear mixer — returns a value roughly in [0, 1]. */
    private float mix() {
        int p1    = pulseOutput(0);
        int p2    = pulseOutput(1);
        int tri   = triOutput();
        int noise = noiseOutput();

        float pulseOut = 0.0f;
        if (p1 + p2 > 0)
            pulseOut = (float) (95.88 / (8128.0 / (p1 + p2) + 100.0));

        float tndOut = 0.0f;
        double tnd = tri / 8227.0 + noise / 12241.0;
        if (tnd > 0)
            tndOut = (float) (159.79 / (1.0 / tnd + 100.0));

        return pulseOut + tndOut;
    }

    private int pulseOutput(int p) {
        if (!pulseEnabled[p])                        return 0;
        if (pulseLenCtr[p] == 0)                     return 0;
        if (DUTY_TABLE[pulseDuty[p]][pulseSeqPos[p]] == 0) return 0;
        if (isSweepMuting(p))                        return 0;
        return pulseEnvConst[p] ? pulseEnvPeriod[p] : pulseEnvelope[p];
    }

    private int triOutput() {
        if (!triEnabled || triLenCtr == 0 || triLinearCtr == 0) return 0;
        return TRI_TABLE[triSeqPos];
    }

    private int noiseOutput() {
        if (!noiseEnabled || noiseLenCtr == 0) return 0;
        if ((noiseShiftReg & 1) == 1)           return 0;
        return noiseEnvConst ? noiseEnvPeriod : noiseEnvelope;
    }

    /** A pulse channel is silenced by sweep when the period is out of range. */
    private boolean isSweepMuting(int p) {
        if (pulsePeriod[p] < 8) return true;
        if (!sweepNegate[p] && sweepShift[p] > 0) {
            return (pulsePeriod[p] + (pulsePeriod[p] >> sweepShift[p])) > 0x7FF;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mute
    // ─────────────────────────────────────────────────────────────────────────

    public void setMuted(boolean muted) { this.muted = muted; }
    public boolean isMuted()            { return muted; }
}
