package com.nes.memory;

/**
 * Standard NES gamepad (8 buttons, serial shift register).
 *
 * Button order (shift register bit 0 first):
 *   A, B, Select, Start, Up, Down, Left, Right
 *
 * Protocol:
 *   Write 1 to $4016 → latch button states into shift register
 *   Write 0 to $4016 → stop latching, enable serial reads
 *   Read  $4016/$4017 → return bit 0 of shift register, then shift right
 */
public class Controller {

    public static final int BTN_A      = 0;
    public static final int BTN_B      = 1;
    public static final int BTN_SELECT = 2;
    public static final int BTN_START  = 3;
    public static final int BTN_UP     = 4;
    public static final int BTN_DOWN   = 5;
    public static final int BTN_LEFT   = 6;
    public static final int BTN_RIGHT  = 7;

    // Current state of all 8 buttons (bit per button)
    private int buttonState;

    // Snapshot latched when strobe goes low; shifted out on each read
    private int shiftRegister;
    private boolean strobe;

    // -------------------------------------------------------------------------
    // Input (called by the UI on key events)
    // -------------------------------------------------------------------------

    /** Press or release a button. button is one of the BTN_* constants. */
    public void setButton(int button, boolean pressed) {
    }

    // -------------------------------------------------------------------------
    // Bus-facing interface
    // -------------------------------------------------------------------------

    /**
     * CPU read from $4016 or $4017.
     * Returns bit 0 of the shift register [0 or 1] and advances it.
     */
    public int read() {
        return 0;
    }

    /**
     * CPU write to $4016.
     * Bit 0 = strobe: while high, shift register continuously mirrors button state.
     * On falling edge (1→0), the current state is latched for serial readout.
     */
    public void write(int data) {
    }
}
