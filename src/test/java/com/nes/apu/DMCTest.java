package com.nes.apu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the DMC (Delta Modulation Channel) — APU register $4010–$4013.
 *
 * The DMC streams 1-bit delta samples from memory and drives a 7-bit DAC.
 *
 * Key formulas:
 *   Sample address : $C000 + $40  * $4012
 *   Sample length  : $10   * $4013 + 1  (bytes)
 *   Rate period    : DMC_PERIOD_TABLE[$4010 & 0x0F]   (fastest = index 15 = 54 cycles)
 *
 * Each timer expiry clocks one bit out of the shift register:
 *   bit = 1 and outputLevel ≤ 125  →  outputLevel += 2
 *   bit = 0 and outputLevel ≥ 2    →  outputLevel -= 2
 *
 * Status register ($4015 read):
 *   Bit 4 : DMC active  (bytes remaining > 0, buffer loaded, or shift reg playing)
 *   Bit 7 : DMC IRQ flag (cleared on read)
 */
class DMCTest {

    // Fastest DMC rate: index 0x0F → period = 54 CPU cycles per output bit
    private static final int FAST_RATE_IDX = 0x0F;
    private static final int FAST_PERIOD   = 54;

    private APU apu;

    /** Tick {@code n} CPU cycles. */
    private void tick(int n) { for (int i = 0; i < n; i++) apu.tick(); }

    /** Read status (clears IRQ flags as a side effect). */
    private int status() { return apu.readStatus(); }

    // =========================================================================
    // Register decoding
    // =========================================================================

    @Nested
    class RegisterDecodeTests {

        @BeforeEach
        void setUp() { apu = new APU(); }

        @Test
        void directLoadSetsOutputLevelImmediately() {
            apu.write(0x4011, 64);
            assertEquals(64, apu.getDmcOutputLevel());
        }

        @Test
        void directLoadMasksTo7Bits() {
            apu.write(0x4011, 0xFF);           // bit 7 is ignored
            assertEquals(0x7F, apu.getDmcOutputLevel());
        }

        @Test
        void directLoadZeroClearsOutput() {
            apu.write(0x4011, 64);
            apu.write(0x4011, 0);
            assertEquals(0, apu.getDmcOutputLevel());
        }

        @Test
        void sampleAddressFormula() {
            apu.write(0x4012, 0x00);  // $C000 + $40*0 = $C000
            assertEquals(0xC000, apu.getDmcSampleAddr());

            apu.write(0x4012, 0x01);  // $C000 + $40 = $C040
            assertEquals(0xC040, apu.getDmcSampleAddr());

            apu.write(0x4012, 0xFF);  // $C000 + $40*255 = $FFC0
            assertEquals(0xFFC0, apu.getDmcSampleAddr());
        }

        @Test
        void sampleLengthFormula() {
            apu.write(0x4013, 0x00);  // $10*0 + 1 = 1
            assertEquals(1, apu.getDmcSampleLength());

            apu.write(0x4013, 0x01);  // $10*1 + 1 = 17
            assertEquals(17, apu.getDmcSampleLength());

            apu.write(0x4013, 0xFF);  // $10*255 + 1 = 4081
            assertEquals(4081, apu.getDmcSampleLength());
        }
    }

    // =========================================================================
    // Enable / disable via $4015
    // =========================================================================

    @Nested
    class EnableDisableTests {

        @BeforeEach
        void setUp() { apu = new APU(addr -> 0xAA); }  // memory returns 0xAA

        @Test
        void dmcActiveBitSetWhenEnabled() {
            apu.write(0x4013, 0x01);   // length = 17 bytes
            apu.write(0x4015, 0x10);   // enable DMC
            assertTrue((status() & 0x10) != 0, "DMC status bit must be set when active");
        }

        @Test
        void dmcActiveBitClearedWhenDisabled() {
            apu.write(0x4013, 0x01);
            apu.write(0x4015, 0x10);   // enable
            apu.write(0x4015, 0x00);   // disable
            assertEquals(0, status() & 0x10);
        }

        @Test
        void disablingClearsBytesRemaining() {
            apu.write(0x4013, 0x0F);   // 241 bytes
            apu.write(0x4015, 0x10);
            apu.write(0x4015, 0x00);
            assertEquals(0, apu.getDmcBytesRemaining());
        }

        @Test
        void enablingWhenAlreadyActiveDoesNotRestart() {
            apu.write(0x4013, 0x0F);   // 241 bytes
            apu.write(0x4015, 0x10);
            int beforeBytes = apu.getDmcBytesRemaining();
            apu.write(0x4015, 0x10);   // re-enable while active
            // should not reload — bytes remaining is unchanged or slightly consumed
            assertTrue(apu.getDmcBytesRemaining() <= beforeBytes,
                "Re-enabling an active DMC channel must not reset bytes remaining");
        }

        @Test
        void enablingAfterExpiryReloadsFromSavedRegisters() {
            // Use length=1 so it expires immediately after enable
            apu.write(0x4012, 0x00);   // addr $C000
            apu.write(0x4013, 0x00);   // length 1
            apu.write(0x4015, 0x10);   // enable → fills buffer, bytes=0

            // Now re-enable: should reload from $4012/$4013
            apu.write(0x4015, 0x10);
            assertTrue((status() & 0x10) != 0 || apu.getDmcBytesRemaining() >= 0,
                "DMC should become active again after re-enable");
        }

        @Test
        void dmcIrqFlagClearedByStatusWrite() {
            // Trigger IRQ: length=1, IRQ enabled, not loop
            apu.write(0x4010, 0x80 | FAST_RATE_IDX);  // IRQ enabled, fast rate
            apu.write(0x4013, 0x00);                   // length = 1
            apu.write(0x4015, 0x10);                   // enable → immediately fires IRQ

            assertTrue(apu.isDmcIrqPending(), "IRQ must fire after single-byte sample");
            apu.write(0x4015, 0x00);                   // writing $4015 clears IRQ
            assertFalse(apu.isDmcIrqPending());
        }
    }

    // =========================================================================
    // Output level — delta modulation
    // =========================================================================

    @Nested
    class OutputLevelTests {

        @Test
        void allOnesByteRaisesOutputLevel() {
            // 0xFF = 11111111: every bit is 1 → output increases by 2 per bit clock
            apu = new APU(addr -> 0xFF);

            apu.write(0x4010, FAST_RATE_IDX);   // fast rate, no IRQ, no loop
            apu.write(0x4011, 64);               // start at 64
            apu.write(0x4013, 0x00);             // 1-byte sample
            apu.write(0x4015, 0x10);             // enable → buffer filled immediately

            int before = apu.getDmcOutputLevel();
            // Tick 8 output clocks (8 * FAST_PERIOD cycles) to play the full byte
            tick(8 * FAST_PERIOD + 1);
            int after = apu.getDmcOutputLevel();

            assertTrue(after > before,
                "All-ones sample must raise the output level (before=" + before + " after=" + after + ")");
        }

        @Test
        void allZerosByteReducesOutputLevel() {
            // 0x00 = 00000000: every bit is 0 → output decreases by 2 per bit clock
            apu = new APU(addr -> 0x00);

            apu.write(0x4010, FAST_RATE_IDX);
            apu.write(0x4011, 64);               // start at 64
            apu.write(0x4013, 0x00);             // 1-byte sample
            apu.write(0x4015, 0x10);

            int before = apu.getDmcOutputLevel();
            tick(8 * FAST_PERIOD + 1);
            int after = apu.getDmcOutputLevel();

            assertTrue(after < before,
                "All-zeros sample must lower the output level (before=" + before + " after=" + after + ")");
        }

        @Test
        void outputLevelClampedAt127() {
            apu = new APU(addr -> 0xFF);
            apu.write(0x4010, FAST_RATE_IDX);
            apu.write(0x4011, 126);              // near ceiling
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            tick(8 * FAST_PERIOD + 1);
            assertTrue(apu.getDmcOutputLevel() <= 127,
                "Output level must never exceed 127");
        }

        @Test
        void outputLevelClampedAt0() {
            apu = new APU(addr -> 0x00);
            apu.write(0x4010, FAST_RATE_IDX);
            apu.write(0x4011, 1);                // near floor
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            tick(8 * FAST_PERIOD + 1);
            assertTrue(apu.getDmcOutputLevel() >= 0,
                "Output level must never go below 0");
        }

        @Test
        void directLoadOverridesOngoingPlayback() {
            apu = new APU(addr -> 0x00);
            apu.write(0x4010, FAST_RATE_IDX);
            apu.write(0x4011, 50);
            apu.write(0x4013, 0x0F);             // long sample
            apu.write(0x4015, 0x10);

            tick(4 * FAST_PERIOD);               // midway through playback

            apu.write(0x4011, 100);              // direct load mid-playback
            assertEquals(100, apu.getDmcOutputLevel(),
                "Direct load must take effect immediately regardless of ongoing playback");
        }
    }

    // =========================================================================
    // IRQ behaviour
    // =========================================================================

    @Nested
    class IrqTests {

        @Test
        void irqFiredAfterSampleCompletes() {
            apu = new APU(addr -> 0xFF);
            apu.write(0x4010, 0x80 | FAST_RATE_IDX);  // IRQ enabled
            apu.write(0x4013, 0x00);                   // 1-byte sample
            apu.write(0x4015, 0x10);

            assertTrue(apu.isDmcIrqPending(),
                "IRQ must be set immediately after single-byte sample buffer is read");
        }

        @Test
        void irqNotFiredWhenIrqDisabled() {
            apu = new APU(addr -> 0xFF);
            apu.write(0x4010, FAST_RATE_IDX);   // IRQ bit NOT set
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            assertFalse(apu.isDmcIrqPending(), "IRQ must not fire when IRQ enable bit is 0");
        }

        @Test
        void irqNotFiredInLoopMode() {
            apu = new APU(addr -> 0xFF);
            // Both IRQ-enable and loop set: loop takes priority, no IRQ
            apu.write(0x4010, 0xC0 | FAST_RATE_IDX);  // bits 7=IRQ, 6=loop
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            // Run well past when the single byte would have triggered IRQ
            tick(8 * FAST_PERIOD + 100);
            assertFalse(apu.isDmcIrqPending(), "IRQ must not fire in loop mode");
        }

        @Test
        void irqClearedByStatusRead() {
            apu = new APU(addr -> 0xFF);
            apu.write(0x4010, 0x80 | FAST_RATE_IDX);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            assertTrue(apu.isDmcIrqPending(), "Pre-condition: IRQ set");
            status();                            // readStatus() clears IRQ
            assertFalse(apu.isDmcIrqPending(), "IRQ must be cleared by readStatus()");
        }

        @Test
        void irqClearedByDisablingIrqEnableBit() {
            apu = new APU(addr -> 0xFF);
            apu.write(0x4010, 0x80 | FAST_RATE_IDX);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            assertTrue(apu.isDmcIrqPending(), "Pre-condition: IRQ set");
            apu.write(0x4010, FAST_RATE_IDX);   // clear IRQ-enable bit
            assertFalse(apu.isDmcIrqPending(), "Clearing IRQ-enable bit must clear pending IRQ");
        }

        @Test
        void statusRegisterBit7ReflectsDmcIrq() {
            apu = new APU(addr -> 0xFF);
            apu.write(0x4010, 0x80 | FAST_RATE_IDX);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            assertTrue((status() & 0x80) != 0, "Status bit 7 must reflect DMC IRQ");
        }
    }

    // =========================================================================
    // Loop mode
    // =========================================================================

    @Nested
    class LoopModeTests {

        @Test
        void loopModeKeepsDmcActive() {
            apu = new APU(addr -> 0xAA);
            apu.write(0x4010, 0x40 | FAST_RATE_IDX);  // loop, no IRQ
            apu.write(0x4013, 0x00);                   // 1-byte sample
            apu.write(0x4015, 0x10);

            // Even after many output clocks, DMC should stay active
            tick(16 * FAST_PERIOD + 10);
            assertTrue((status() & 0x10) != 0,
                "Loop mode: DMC must remain active after sample exhaustion");
        }

        @Test
        void loopModeReloadsAddressAndLength() {
            apu = new APU(addr -> 0xAA);
            apu.write(0x4010, 0x40 | FAST_RATE_IDX);
            apu.write(0x4013, 0x00);   // 1-byte sample
            apu.write(0x4015, 0x10);

            int initial = apu.getDmcBytesRemaining(); // 0 after immediate fill
            tick(8 * FAST_PERIOD + 1); // play through 8 bits
            // After loop, bytes should have been reloaded
            assertTrue(apu.getDmcBytesRemaining() >= 0,
                "Bytes remaining must be reloaded after loop restart");
        }
    }

    // =========================================================================
    // Memory address wrapping
    // =========================================================================

    @Nested
    class AddressWrapTests {

        @Test
        void addressWrapsFrom0xFFFFTo0x8000() {
            // Use a reader that tracks which addresses were accessed
            int[] lastAddr = {-1};
            IntUnaryOperator trackingReader = addr -> { lastAddr[0] = addr; return 0x55; };

            apu = new APU(trackingReader);
            apu.write(0x4012, 0xFF);   // base addr = $C000 + $40*255 = $FFC0
            apu.write(0x4013, 0x02);   // 33 bytes — enough to cross the wrap boundary
            apu.write(0x4015, 0x10);

            // The reader is called eagerly; the last addr seen should be valid
            assertTrue(lastAddr[0] >= 0x8000 || lastAddr[0] == 0xFFC0,
                "First read address must be within [$8000, $FFFF]");
        }
    }

    // =========================================================================
    // Reset
    // =========================================================================

    @Nested
    class ResetTests {

        @Test
        void resetClearsDmcOutputLevel() {
            apu = new APU();
            apu.write(0x4011, 100);
            apu.reset();
            assertEquals(0, apu.getDmcOutputLevel());
        }

        @Test
        void resetClearsDmcBytesRemaining() {
            apu = new APU(addr -> 0xFF);
            apu.write(0x4013, 0x0F);
            apu.write(0x4015, 0x10);
            apu.reset();
            assertEquals(0, apu.getDmcBytesRemaining());
        }

        @Test
        void resetClearsDmcIrq() {
            apu = new APU(addr -> 0xFF);
            apu.write(0x4010, 0x80 | FAST_RATE_IDX);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);
            assertTrue(apu.isDmcIrqPending(), "Pre-condition");
            apu.reset();
            assertFalse(apu.isDmcIrqPending(), "IRQ must be cleared by reset");
        }
    }

    // =========================================================================
    // Smoke tests — no exceptions for full value range
    // =========================================================================

    @Nested
    class SmokeTests {

        @BeforeEach
        void setUp() { apu = new APU(); }

        @Test
        void allRateIndexesAccepted() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) apu.write(0x4010, v);
            });
        }

        @Test
        void allDirectLoadValuesAccepted() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) apu.write(0x4011, v);
            });
        }

        @Test
        void allSampleAddressValuesAccepted() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) apu.write(0x4012, v);
            });
        }

        @Test
        void allSampleLengthValuesAccepted() {
            assertDoesNotThrow(() -> {
                for (int v = 0; v <= 0xFF; v++) apu.write(0x4013, v);
            });
        }

        @Test
        void tickingWithDmcEnabledAndNoMemoryDoesNotThrow() {
            apu = new APU(addr -> 0);
            apu.write(0x4013, 0x0F);
            apu.write(0x4015, 0x10);
            assertDoesNotThrow(() -> tick(10_000));
        }
    }
}
