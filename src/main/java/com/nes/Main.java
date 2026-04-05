package com.nes;

import com.nes.memory.Cartridge;
import com.nes.memory.Controller;
import com.nes.ui.Screen;

import javax.swing.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;

public class Main {

    public static void main(String[] args) {

        String romPath = args.length > 0 ? args[0] : "roms/Donkey Kong (World) (Rev A).nes";

        // ----------------------------------------------------------------
        // Cartridge
        // ----------------------------------------------------------------
        Cartridge cartridge = new Cartridge();
        try {
            cartridge.load(romPath);
        } catch (IOException e) {
            System.err.println("Failed to load ROM: " + e.getMessage());
            System.exit(1);
        }

        // ----------------------------------------------------------------
        // NES
        // ----------------------------------------------------------------
        NES nes = new NES();

        Controller controller1 = new Controller();
        Controller controller2 = new Controller();

        nes.insert(cartridge);
        nes.setControllers(controller1, controller2);
        nes.reset();

        // ----------------------------------------------------------------
        // Window + keyboard
        // ----------------------------------------------------------------
        SwingUtilities.invokeLater(() -> {
            Screen screen = new Screen(3);
            JFrame frame = Screen.openWindow("NES Emulator", screen);

            frame.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e)  { handleKey(e, true); }
                @Override public void keyReleased(KeyEvent e) { handleKey(e, false); }

                private void handleKey(KeyEvent e, boolean pressed) {
                    int btn = keyToButton(e.getKeyCode());
                    if (btn >= 0) {
                        controller1.setButton(btn, pressed);
                        screen.updateController(controller1.getButtonState());
                    }
                }

                private int keyToButton(int key) {
                    switch (key) {
                        case KeyEvent.VK_X:     return Controller.BTN_A;
                        case KeyEvent.VK_Z:     return Controller.BTN_B;
                        case KeyEvent.VK_Q:     return Controller.BTN_SELECT;
                        case KeyEvent.VK_W:     return Controller.BTN_START;
                        case KeyEvent.VK_UP:    return Controller.BTN_UP;
                        case KeyEvent.VK_DOWN:  return Controller.BTN_DOWN;
                        case KeyEvent.VK_LEFT:  return Controller.BTN_LEFT;
                        case KeyEvent.VK_RIGHT: return Controller.BTN_RIGHT;
                        default:                return -1;
                    }
                }
            });

            // 60 FPS game loop
            new Timer(1000 / 60, e -> {
                nes.stepFrame();
                screen.updateFrame(nes.getFrameBuffer());
                screen.updateClock(nes.getMasterClock(), nes.getFrameCount());
            }).start();
        });
    }
}
