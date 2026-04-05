package com.nes.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the NES controller shift-register protocol.
 *
 * The NES reads buttons by:
 *   1. Write 1 to $4016 (strobe high — continuously mirrors live state)
 *   2. Write 0 to $4016 (strobe low  — latches current state)
 *   3. Read $4016 eight times: returns A, B, Sel, Start, Up, Down, Left, Right
 *   4. Further reads return 1 (open-bus / register empty)
 */
class ControllerTest {

    private Controller ctrl;

    @BeforeEach
    void setUp() {
        ctrl = new Controller();
    }

    // =========================================================================
    // Button state
    // =========================================================================

    @Nested
    class ButtonState {

        @Test void initially_all_released() {
            assertEquals(0, ctrl.getButtonState());
        }

        @Test void press_sets_bit() {
            ctrl.setButton(Controller.BTN_A, true);
            assertEquals(1, ctrl.getButtonState());
        }

        @Test void release_clears_bit() {
            ctrl.setButton(Controller.BTN_A, true);
            ctrl.setButton(Controller.BTN_A, false);
            assertEquals(0, ctrl.getButtonState());
        }

        @Test void multiple_buttons_independent() {
            ctrl.setButton(Controller.BTN_UP,    true);
            ctrl.setButton(Controller.BTN_RIGHT,  true);
            int state = ctrl.getButtonState();
            assertTrue((state & (1 << Controller.BTN_UP))    != 0);
            assertTrue((state & (1 << Controller.BTN_RIGHT)) != 0);
            assertTrue((state & (1 << Controller.BTN_A))     == 0);
        }

        @Test void all_buttons_have_distinct_bits() {
            int[] buttons = {
                Controller.BTN_A, Controller.BTN_B, Controller.BTN_SELECT,
                Controller.BTN_START, Controller.BTN_UP, Controller.BTN_DOWN,
                Controller.BTN_LEFT, Controller.BTN_RIGHT
            };
            int mask = 0;
            for (int btn : buttons) {
                int bit = 1 << btn;
                assertEquals(0, mask & bit, "Duplicate bit for button " + btn);
                mask |= bit;
            }
            assertEquals(0xFF, mask, "Buttons should cover all 8 bits");
        }
    }

    // =========================================================================
    // Strobe protocol
    // =========================================================================

    @Nested
    class StrobeProtocol {

        /** Drive strobe high then low to latch the given button state. */
        private void latch(int... pressed) {
            for (int btn : pressed) ctrl.setButton(btn, true);
            ctrl.write(1); // strobe high
            ctrl.write(0); // strobe low → latch
        }

        @Test void strobe_high_always_returns_A_button() {
            ctrl.setButton(Controller.BTN_A, true);
            ctrl.write(1); // strobe high
            assertEquals(1, ctrl.read()); // A pressed
            ctrl.setButton(Controller.BTN_A, false);
            assertEquals(0, ctrl.read()); // A released (live)
        }

        @Test void latch_then_read_8_buttons_in_order() {
            // Press Start (bit 3) and Right (bit 7)
            latch(Controller.BTN_START, Controller.BTN_RIGHT);

            int[] expected = {0, 0, 0, 1, 0, 0, 0, 1}; // A B Sel Start Up Down Left Right
            for (int i = 0; i < 8; i++) {
                assertEquals(expected[i], ctrl.read() & 1,
                        "Bit " + i + " mismatch");
            }
        }

        @Test void reads_after_8_return_1() {
            latch(); // nothing pressed
            for (int i = 0; i < 8; i++) ctrl.read(); // drain
            assertEquals(1, ctrl.read() & 1, "Read 9 should return 1");
            assertEquals(1, ctrl.read() & 1, "Read 10 should return 1");
        }

        @Test void register_stable_after_latch() {
            ctrl.setButton(Controller.BTN_B, true);
            ctrl.write(1);
            ctrl.write(0); // latch with B pressed
            ctrl.setButton(Controller.BTN_B, false); // release AFTER latch

            ctrl.read(); // A → 0
            assertEquals(1, ctrl.read() & 1, "B should read as pressed (latched)");
        }

        @Test void new_latch_reloads_shift_register() {
            latch(Controller.BTN_A);
            assertEquals(1, ctrl.read() & 1); // A bit

            // Latch again with A released
            ctrl.setButton(Controller.BTN_A, false);
            latch();
            assertEquals(0, ctrl.read() & 1); // A now 0
        }

        @Test void no_press_reads_all_zeros_then_ones() {
            latch(); // no buttons
            for (int i = 0; i < 8; i++) {
                assertEquals(0, ctrl.read() & 1, "Bit " + i + " should be 0");
            }
            assertEquals(1, ctrl.read() & 1, "Post-register read should be 1");
        }

        @Test void all_buttons_pressed_reads_8_ones() {
            for (int b = 0; b < 8; b++) ctrl.setButton(b, true);
            latch();
            for (int i = 0; i < 8; i++) {
                assertEquals(1, ctrl.read() & 1, "Bit " + i + " should be 1");
            }
        }
    }
}
