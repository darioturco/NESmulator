package com.nes.memory;

/**
 * Standard NES gamepad (4021 shift register, 8 buttons).
 *
 * Button bit positions (shift register order, bit 0 first):
 *   0=A  1=B  2=Select  3=Start  4=Up  5=Down  6=Left  7=Right
 *
 * Protocol:
 *   Write 1 to $4016 → strobe high: shift register continuously mirrors live button state
 *   Write 0 to $4016 → strobe low: latch current state, enable serial read-out
 *   Read  $4016/$4017 → return bit 0 of shift register, shift right by 1
 *   After all 8 bits are shifted out, reads return 1 (open-bus behaviour)
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

    /** Live state of all 8 buttons (one bit per button, bit 0 = A). */
    private int buttonState;

    /** Snapshot latched on strobe falling edge; shifted out one bit per read. */
    private int shiftRegister;

    /** True while the strobe line is held high. */
    private boolean strobe;

    // -------------------------------------------------------------------------
    // Input (called by the UI on key events)
    // -------------------------------------------------------------------------

    /**
     * Press or release a button.
     *
     * @param button  one of the BTN_* constants
     * @param pressed true = pressed, false = released
     */
    public void setButton(int button, boolean pressed) {
        if (pressed) {
            buttonState |=  (1 << button);
        } else {
            buttonState &= ~(1 << button);
        }
    }

    /** Returns the current live button state bitmask (bit 0 = A … bit 7 = Right). */
    public int getButtonState() {
        return buttonState;
    }

    // -------------------------------------------------------------------------
    // Bus-facing interface
    // -------------------------------------------------------------------------

    /**
     * CPU read from $4016 (controller 1) or $4017 (controller 2).
     *
     * While strobe is high the live state of button A is returned on every read.
     * While strobe is low the shift register is advanced: bit 0 is returned
     * and the register is shifted right.  Once all 8 bits are consumed,
     * subsequent reads return 1.
     *
     * @return 0 or 1 (bit 0 of the shift register)
     */
    public int read() {
        if (strobe) {
            return buttonState & 1; // A button while strobe is held
        }
        int bit = shiftRegister & 1;
        shiftRegister = (shiftRegister >> 1) | 0x80; // shift in 1s after register empties
        return bit;
    }

    /**
     * CPU write to $4016.
     *
     * Bit 0 is the strobe line.  While high, the shift register continuously
     * reloads from the live button state.  On the falling edge (1 → 0) the
     * current button state is frozen into the shift register.
     *
     * @param data byte written by the CPU
     */
    public void write(int data) {
        boolean newStrobe = (data & 1) != 0;
        if (strobe && !newStrobe) {
            // Falling edge: latch current button state
            shiftRegister = buttonState;
        }
        strobe = newStrobe;
        if (strobe) {
            shiftRegister = buttonState; // continuously mirror while high
        }
    }
}
