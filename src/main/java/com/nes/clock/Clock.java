package com.nes.clock;

/**
 * Controls emulation timing, keeping the game loop running at a stable 60 FPS.
 * Uses a sleep/busy-wait hybrid: sleeps for most of the frame budget, then
 * busy-waits the final millisecond for sub-millisecond precision.
 */
public class Clock {

    public static final int TARGET_FPS = 60;
    private static final long FRAME_NANOS = 1_000_000_000L / TARGET_FPS; // ~16,666,667 ns

    private long lastFrameNanos;

    /** Reset the internal timer. Must be called once before the game loop starts. */
    public void reset() {
        lastFrameNanos = System.nanoTime();
    }

    /**
     * Block the current thread until the next frame boundary.
     * Call once per completed frame.
     */
    public void sync() {
        long target = lastFrameNanos + FRAME_NANOS;

        // Sleep for most of the remaining budget (leave ~1 ms for busy-wait precision)
        long sleepUntil = target - 1_000_000L;
        long now = System.nanoTime();
        if (now < sleepUntil) {
            long sleepNanos = sleepUntil - now;
            try {
                Thread.sleep(sleepNanos / 1_000_000, (int)(sleepNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // Busy-wait the final millisecond for precise timing
        while (System.nanoTime() < target) {
            Thread.yield();
        }

        lastFrameNanos = System.nanoTime();
    }
}
