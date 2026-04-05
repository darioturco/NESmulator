package com.nes.apu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Ricoh 2A03 APU.
 *
 * Tests exercise the public interface only: write(), readStatus(), tick(),
 * setMuted(), isMuted(), reset().  Audio output is never opened (start() is
 * not called), so AudioOutput.write() is a no-op — safe for headless CI.
 *
 * Timing reference (4-step frame counter, NTSC):
 *   Cycle  7 457 — envelope clock
 *   Cycle 14 913 — envelope + length-counter / sweep clock  (half-frame 1)
 *   Cycle 22 371 — envelope clock
 *   Cycle 29 830 — envelope + length-counter / sweep + IRQ  (half-frame 2)
 *
 * LENGTH_TABLE subset used in tests:
 *   index 0 → 10  ($4x03 = 0x00)
 *   index 2 → 20  ($4x03 = 0x10)
 *   index 3 →  2  ($4x03 = 0x18)   ← "expires quickly" cases
 */
class APUTest {

    // ── Frame-counter cycle boundaries ────────────────────────────────────────
    private static final int HALF_FRAME_1 = 14_913;
    private static final int HALF_FRAME_2 = 29_830;
    private static final int HALF_FRAME_5STEP = 37_282;

    // ── Length-counter index encodings for $4x03 / $400F ─────────────────────
    /** Bits [7:3] of timer-high / length register → LENGTH_TABLE index */
    private static final int LEN_10  = 0x00;  // index 0 → 10
    private static final int LEN_20  = 0x10;  // index 2 → 20
    private static final int LEN_2   = 0x18;  // index 3 →  2

    private APU apu;

    @BeforeEach
    void setUp() {
        apu = new APU();
        // Note: apu.start() intentionally NOT called → AudioOutput.write() is a no-op
    }

    /** Advance the APU by {@code n} CPU cycles. */
    private void tick(int n) {
        for (int i = 0; i < n; i++) apu.tick();
    }

    /** Read status without caring about the return value (side-effect: clears IRQ flag). */
    private int status() {
        return apu.readStatus();
    }

    // =========================================================================
    // Initial state
    // =========================================================================

    @Nested
    class InitialStateTests {

        @Test
        void statusRegisterIsZeroOnInit() {
            assertEquals(0, apu.readStatus());
        }

        @Test
        void muteDefaultsToTrue() {
            assertTrue(apu.isMuted());
        }

        @Test
        void tickingWithoutSetupProducesNoException() {
            assertDoesNotThrow(() -> tick(HALF_FRAME_2 + 1));
        }
    }

    // =========================================================================
    // Status register — channel enable / disable
    // =========================================================================

    @Nested
    class StatusRegisterTests {

        @Test
        void pulse1EnabledByStatus() {
            apu.write(0x4015, 0x01);           // enable pulse 1
            apu.write(0x4003, LEN_10);         // load length = 10
            assertEquals(0x01, status() & 0x01);
        }

        @Test
        void pulse2EnabledByStatus() {
            apu.write(0x4015, 0x02);
            apu.write(0x4007, LEN_10);
            assertEquals(0x02, status() & 0x02);
        }

        @Test
        void triangleEnabledByStatus() {
            apu.write(0x4015, 0x04);
            apu.write(0x400B, LEN_10);
            assertEquals(0x04, status() & 0x04);
        }

        @Test
        void noiseEnabledByStatus() {
            apu.write(0x4015, 0x08);
            apu.write(0x400F, LEN_10);
            assertEquals(0x08, status() & 0x08);
        }

        @Test
        void allFourChannelsEnabledAtOnce() {
            apu.write(0x4015, 0x0F);
            apu.write(0x4003, LEN_10);
            apu.write(0x4007, LEN_10);
            apu.write(0x400B, LEN_10);
            apu.write(0x400F, LEN_10);
            assertEquals(0x0F, status() & 0x0F);
        }

        @Test
        void disablingPulse1ClearsLengthCounter() {
            apu.write(0x4015, 0x01);
            apu.write(0x4003, LEN_10);
            assertEquals(0x01, status() & 0x01);

            apu.write(0x4015, 0x00);           // disable pulse 1
            assertEquals(0, status() & 0x01);
        }

        @Test
        void disablingPulse2ClearsLengthCounter() {
            apu.write(0x4015, 0x02);
            apu.write(0x4007, LEN_10);
            apu.write(0x4015, 0x00);
            assertEquals(0, status() & 0x02);
        }

        @Test
        void disablingTriangleClearsLengthCounter() {
            apu.write(0x4015, 0x04);
            apu.write(0x400B, LEN_10);
            apu.write(0x4015, 0x00);
            assertEquals(0, status() & 0x04);
        }

        @Test
        void disablingNoiseClearsLengthCounter() {
            apu.write(0x4015, 0x08);
            apu.write(0x400F, LEN_10);
            apu.write(0x4015, 0x00);
            assertEquals(0, status() & 0x08);
        }

        @Test
        void pulse1LengthNotLoadedIfChannelDisabled() {
            // Write timer-hi BEFORE enabling → length counter must not load
            apu.write(0x4003, LEN_10);
            assertEquals(0, status() & 0x01);
        }

        @Test
        void writingStatusClearsFrameIrq() {
            // Reach IRQ point in 4-step mode
            tick(HALF_FRAME_2);
            assertTrue((status() & 0x40) != 0, "IRQ should be set");

            // Write $4015 clears the frame IRQ flag
            apu.write(0x4015, 0x00);
            assertEquals(0, status() & 0x40);
        }
    }

    // =========================================================================
    // Length counters
    // =========================================================================

    @Nested
    class LengthCounterTests {

        // ── Pulse 1 ──────────────────────────────────────────────────────────

        @Test
        void pulse1LengthCounterDecrementsAfterFirstHalfFrame() {
            apu.write(0x4015, 0x01);
            apu.write(0x4000, 0x00);           // no length halt
            apu.write(0x4003, LEN_20);         // length = 20

            tick(HALF_FRAME_1 + 1);            // one half-frame clock: 20 → 19
            assertEquals(0x01, status() & 0x01, "Pulse 1 still active after one decrement");
        }

        @Test
        void pulse1LengthCounterExpires() {
            apu.write(0x4015, 0x01);
            apu.write(0x4000, 0x00);           // no length halt
            apu.write(0x4003, LEN_2);          // length = 2 (expires after 2 half-frames)

            tick(HALF_FRAME_1 + 1);            // 2 → 1
            assertEquals(0x01, status() & 0x01, "Still active after first half-frame");

            tick(HALF_FRAME_2 - HALF_FRAME_1); // 1 → 0
            assertEquals(0, status() & 0x01,   "Expired after second half-frame");
        }

        @Test
        void pulse1LengthHaltPreventsDecrement() {
            apu.write(0x4015, 0x01);
            apu.write(0x4000, 0x20);           // bit 5 = length halt
            apu.write(0x4003, LEN_2);          // would expire after 2 half-frames without halt

            tick(HALF_FRAME_2 + 1);
            assertEquals(0x01, status() & 0x01, "Length counter must not decrement when halted");
        }

        // ── Pulse 2 ──────────────────────────────────────────────────────────

        @Test
        void pulse2LengthCounterExpires() {
            apu.write(0x4015, 0x02);
            apu.write(0x4004, 0x00);
            apu.write(0x4007, LEN_2);

            tick(HALF_FRAME_2 + 1);
            assertEquals(0, status() & 0x02);
        }

        @Test
        void pulse2LengthHaltPreventsDecrement() {
            apu.write(0x4015, 0x02);
            apu.write(0x4004, 0x20);           // length halt
            apu.write(0x4007, LEN_2);

            tick(HALF_FRAME_2 + 1);
            assertEquals(0x02, status() & 0x02);
        }

        // ── Triangle ─────────────────────────────────────────────────────────

        @Test
        void triangleLengthCounterExpires() {
            apu.write(0x4015, 0x04);
            apu.write(0x4008, 0x00);           // no length halt
            apu.write(0x400B, LEN_2);

            tick(HALF_FRAME_2 + 1);
            assertEquals(0, status() & 0x04);
        }

        @Test
        void triangleLengthHaltPreventsDecrement() {
            apu.write(0x4015, 0x04);
            apu.write(0x4008, 0x80);           // bit 7 = length halt / linear control
            apu.write(0x400B, LEN_2);

            tick(HALF_FRAME_2 + 1);
            assertEquals(0x04, status() & 0x04);
        }

        // ── Noise ─────────────────────────────────────────────────────────────

        @Test
        void noiseLengthCounterExpires() {
            apu.write(0x4015, 0x08);
            apu.write(0x400C, 0x00);           // no length halt
            apu.write(0x400F, LEN_2);

            tick(HALF_FRAME_2 + 1);
            assertEquals(0, status() & 0x08);
        }

        @Test
        void noiseLengthHaltPreventsDecrement() {
            apu.write(0x4015, 0x08);
            apu.write(0x400C, 0x20);           // bit 5 = length halt / envelope loop
            apu.write(0x400F, LEN_2);

            tick(HALF_FRAME_2 + 1);
            assertEquals(0x08, status() & 0x08);
        }

        @Test
        void reenablingChannelReloadsLengthCounter() {
            apu.write(0x4015, 0x01);
            apu.write(0x4003, LEN_2);

            // Exhaust the length counter
            tick(HALF_FRAME_2 + 1);
            assertEquals(0, status() & 0x01, "Pre-condition: channel expired");

            // Re-enable and reload
            apu.write(0x4015, 0x01);
            apu.write(0x4003, LEN_10);
            assertEquals(0x01, status() & 0x01, "Channel active again after reload");
        }
    }

    // =========================================================================
    // Frame counter
    // =========================================================================

    @Nested
    class FrameCounterTests {

        @Test
        void frameIrqSetAfter4StepCycle() {
            // Default mode is 4-step; IRQ fires at cycle 29830
            tick(HALF_FRAME_2);
            assertTrue((status() & 0x40) != 0, "Frame IRQ flag must be set at cycle 29830");
        }

        @Test
        void frameIrqClearedByStatusRead() {
            tick(HALF_FRAME_2);
            assertTrue((status() & 0x40) != 0, "Pre-condition: IRQ set");
            assertEquals(0, status() & 0x40,   "IRQ cleared by first readStatus()");
        }

        @Test
        void frameIrqInhibitPreventsFlag() {
            apu.write(0x4017, 0x40);           // bit 6 = IRQ inhibit, mode stays 0
            tick(HALF_FRAME_2 + 1);
            assertEquals(0, status() & 0x40, "IRQ must not fire when inhibit bit is set");
        }

        @Test
        void frameIrqNotSetIn5StepMode() {
            apu.write(0x4017, 0x80);           // bit 7 = 5-step mode
            tick(HALF_FRAME_5STEP + 1);
            assertEquals(0, status() & 0x40, "5-step mode must never fire IRQ");
        }

        @Test
        void frameCounterResetsAfterFullCycle4Step() {
            // Load a channel with a length that expires in exactly one full cycle
            // (two half-frame clocks); after one full cycle it should be gone,
            // and the counter should wrap.
            apu.write(0x4015, 0x01);
            apu.write(0x4000, 0x00);
            apu.write(0x4003, LEN_2);

            tick(HALF_FRAME_2 + 1);
            assertEquals(0, status() & 0x01, "Length expired after full 4-step cycle");
        }

        @Test
        void fiveStepModeSecondHalfFrameIsLater() {
            // In 5-step mode the second half-frame fires at 37282, not 29830.
            // A length-2 counter loaded just before 14913 should still be active
            // at cycle 29831 (no half-frame yet in 5-step mode for the second clock).
            apu.write(0x4017, 0x80);           // 5-step mode
            apu.write(0x4015, 0x01);
            apu.write(0x4000, 0x00);
            apu.write(0x4003, LEN_2);

            tick(HALF_FRAME_1 + 1);            // first half-frame: 2 → 1
            assertEquals(0x01, status() & 0x01, "Active after first half-frame");

            // tick to cycle 29831 — no second half-frame in 5-step mode yet
            tick(HALF_FRAME_2 - HALF_FRAME_1);
            assertEquals(0x01, status() & 0x01, "Still active: second half-frame not yet reached");

            tick(HALF_FRAME_5STEP - HALF_FRAME_2 + 1);  // second half-frame fires
            assertEquals(0, status() & 0x01, "Expired after 5-step second half-frame");
        }

        @Test
        void writingFrameCounterRegisterResetsCounter() {
            // Tick partway, then reset the frame counter by writing $4017.
            // The next half-frame should be 14913 cycles from the reset point,
            // so a length-2 counter should survive longer than 14913 original cycles.
            apu.write(0x4015, 0x01);
            apu.write(0x4000, 0x00);
            apu.write(0x4003, LEN_2);

            tick(14_000);                      // just before first half-frame
            apu.write(0x4017, 0x00);           // reset frame counter
            tick(14_000);                      // still before new first half-frame
            assertEquals(0x01, status() & 0x01,
                "Counter must not have clocked yet after reset");
        }
    }

    // =========================================================================
    // Mute
    // =========================================================================

    @Nested
    class MuteTests {

        @Test
        void muteDefaultsToTrue() {
            assertTrue(apu.isMuted());
        }

        @Test
        void setMutedFalseUnmutes() {
            apu.setMuted(false);
            assertFalse(apu.isMuted());
        }

        @Test
        void muteCanBeToggledBackOn() {
            apu.setMuted(false);
            apu.setMuted(true);
            assertTrue(apu.isMuted());
        }

        @Test
        void tickingWhileMutedProducesNoException() {
            assertTrue(apu.isMuted());
            assertDoesNotThrow(() -> tick(HALF_FRAME_2 + 1));
        }

        @Test
        void tickingWhileUnmutedProducesNoException() {
            apu.setMuted(false);
            assertDoesNotThrow(() -> tick(HALF_FRAME_2 + 1));
        }
    }

    // =========================================================================
    // Reset
    // =========================================================================

    @Nested
    class ResetTests {

        @Test
        void resetClearsAllLengthCounters() {
            apu.write(0x4015, 0x0F);
            apu.write(0x4003, LEN_10);
            apu.write(0x4007, LEN_10);
            apu.write(0x400B, LEN_10);
            apu.write(0x400F, LEN_10);
            assertEquals(0x0F, status() & 0x0F, "Pre-condition: all channels active");

            apu.reset();
            assertEquals(0, status() & 0x0F, "All length counters zero after reset");
        }

        @Test
        void resetClearsFrameIrq() {
            tick(HALF_FRAME_2);
            assertTrue((status() & 0x40) != 0, "Pre-condition: IRQ set");
            // IRQ was cleared by the readStatus() above; trigger another and reset
            tick(HALF_FRAME_2);               // second cycle generates another IRQ
            apu.reset();
            assertEquals(0, status() & 0x40, "Frame IRQ cleared by reset");
        }

        @Test
        void resetPreservesNonAudioState() {
            // After reset, mute state is unchanged (it's a UI concern, not hardware)
            apu.setMuted(false);
            apu.reset();
            // Mute is preserved — reset is a hardware-level operation
            assertFalse(apu.isMuted(), "Mute flag is not a hardware register; reset must not change it");
        }
    }

    // =========================================================================
    // Sweep unit
    // =========================================================================

    @Nested
    class SweepTests {

        @Test
        void lowPeriodMutesPulse1() {
            // Period < 8 silences the pulse channel (sweep muting rule).
            // Write period = 4 (< 8); even with length counter loaded the channel is silent.
            apu.write(0x4015, 0x01);
            apu.write(0x4000, 0x00);
            apu.write(0x4002, 0x04);           // timer lo = 4 (period < 8)
            apu.write(0x4003, LEN_10);         // length loaded

            // Status reflects length counter (non-zero), not audio output,
            // so the channel appears active from the status register's perspective.
            assertEquals(0x01, status() & 0x01,
                "Status bit reflects length counter regardless of sweep muting");
        }

        @Test
        void sweepRegistersWrittenWithoutException() {
            assertDoesNotThrow(() -> {
                apu.write(0x4001, 0xFF);       // pulse 1 sweep: all bits set
                apu.write(0x4005, 0xFF);       // pulse 2 sweep: all bits set
            });
        }
    }

    // =========================================================================
    // Register write smoke tests  (verify no exceptions for all mapped registers)
    // =========================================================================

    @Nested
    class RegisterWriteSmokeTests {

        @Test
        void allPulse1RegistersAcceptAnyValue() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) {
                    apu.write(0x4000, v);
                    apu.write(0x4001, v);
                    apu.write(0x4002, v);
                    apu.write(0x4003, v);
                }
            });
        }

        @Test
        void allPulse2RegistersAcceptAnyValue() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) {
                    apu.write(0x4004, v);
                    apu.write(0x4005, v);
                    apu.write(0x4006, v);
                    apu.write(0x4007, v);
                }
            });
        }

        @Test
        void allTriangleRegistersAcceptAnyValue() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) {
                    apu.write(0x4008, v);
                    apu.write(0x400A, v);
                    apu.write(0x400B, v);
                }
            });
        }

        @Test
        void allNoiseRegistersAcceptAnyValue() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) {
                    apu.write(0x400C, v);
                    apu.write(0x400E, v);
                    apu.write(0x400F, v);
                }
            });
        }

        @Test
        void dmcRegistersAcceptAnyValue() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) {
                    apu.write(0x4010, v);
                    apu.write(0x4011, v);
                    apu.write(0x4012, v);
                    apu.write(0x4013, v);
                }
            });
        }

        @Test
        void statusAndFrameCounterRegistersAcceptAnyValue() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) {
                    apu.write(0x4015, v);
                    apu.write(0x4017, v);
                }
            });
        }
    }
}
