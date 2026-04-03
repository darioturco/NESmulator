package com.nes;

/**
 * Controls emulation timing, keeping the main loop running at a stable 60 FPS.
 */
public class Clock {

    public static final int TARGET_FPS = 60;

    private long lastFrameNanos;
    private double actualFps;

    public Clock() {
    }

    /**
     * Sleep the current thread as needed so that frames are spaced
     * 1/TARGET_FPS seconds apart. Call once per completed frame.
     */
    public void sync() {
    }

    /** Returns the measured FPS from the last frame interval. */
    public double getFps() {
        return actualFps;
    }

    /** Reset the internal timer (call before starting the main loop). */
    public void reset() {
    }
}
