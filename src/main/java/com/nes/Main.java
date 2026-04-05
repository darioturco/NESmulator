package com.nes;

import com.nes.memory.Cartridge;
import com.nes.memory.Controller;
import com.nes.ppu.PPU;
import com.nes.ui.Screen;

import javax.swing.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public class Main {

    public static void main(String[] args) {

        // ----------------------------------------------------------------
        // Controller
        // ----------------------------------------------------------------
        Controller controller1 = new Controller();

        // ----------------------------------------------------------------
        // PPU + demo pattern
        // ----------------------------------------------------------------
        final int[] chrRam = new int[0x2000];

        Cartridge cart = new Cartridge() {
            @Override public int        ppuRead (int a)        { return chrRam[a & 0x1FFF]; }
            @Override public void       ppuWrite(int a, int d) { chrRam[a & 0x1FFF] = d & 0xFF; }
            @Override public MirrorMode getMirrorMode()        { return MirrorMode.VERTICAL; }
        };

        PPU ppu = new PPU();
        ppu.setCartridge(cart);
        ppu.reset();

        // Tile $01 – solid block
        int[] solid = { 0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
                        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 };
        // Tile $02 – hollow box
        int[] box   = { 0xFF,0x81,0x81,0x81,0x81,0x81,0x81,0xFF,
                        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 };
        // Tile $03 – checkerboard
        int[] check = { 0xAA,0x55,0xAA,0x55,0xAA,0x55,0xAA,0x55,
                        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 };
        // Tile $04 – cross
        int[] cross = { 0x18,0x18,0x18,0xFF,0xFF,0x18,0x18,0x18,
                        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 };

        int[][] tiles = { solid, box, check, cross };
        for (int i = 0; i < tiles.length; i++)
            for (int b = 0; b < 16; b++) chrRam[(i + 1) * 16 + b] = tiles[i][b];

        ppu.writeRegister(1, 0x00);       // rendering off during setup

        ppu.writeRegister(6, 0x20);
        ppu.writeRegister(6, 0x00);
        for (int row = 0; row < 30; row++) {
            for (int col = 0; col < 32; col++) {
                boolean border      = row == 0 || row == 29 || col == 0  || col == 31;
                boolean innerBorder = row == 1 || row == 28 || col == 1  || col == 30;
                int tile = border ? 0x01 : innerBorder ? 0x02
                         : ((row + col) & 1) == 0 ? 0x03 : 0x04;
                ppu.writeRegister(7, tile);
            }
        }
        for (int i = 0; i < 64; i++) ppu.writeRegister(7, 0x00);

        ppu.writeRegister(6, 0x3F);
        ppu.writeRegister(6, 0x00);
        ppu.writeRegister(7, 0x0F); // black
        ppu.writeRegister(7, 0x20); // white
        ppu.writeRegister(7, 0x10); // light grey
        ppu.writeRegister(7, 0x00); // dark grey

        ppu.writeRegister(0, 0x00); // PPUCTRL: NT0
        ppu.writeRegister(5, 0x00); // PPUSCROLL X = 0
        ppu.writeRegister(5, 0x00); // PPUSCROLL Y = 0
        ppu.writeRegister(1, 0x08); // enable background

        // Prime the pipeline
        while (!ppu.isFrameComplete()) ppu.tick();
        ppu.clearFrameComplete();
        while (!ppu.isFrameComplete()) ppu.tick();

        // ----------------------------------------------------------------
        // Window + keyboard
        // ----------------------------------------------------------------
        SwingUtilities.invokeLater(() -> {
            Screen screen = new Screen(3);
            JFrame frame = Screen.openWindow("NES Emulator", screen);

            // Keyboard → Controller 1
            //   Arrow keys → D-Pad
            //   Z → B,  X → A
            //   Backspace → Select,  Enter → Start
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
                        case KeyEvent.VK_UP:        return Controller.BTN_UP;
                        case KeyEvent.VK_DOWN:      return Controller.BTN_DOWN;
                        case KeyEvent.VK_LEFT:      return Controller.BTN_LEFT;
                        case KeyEvent.VK_RIGHT:     return Controller.BTN_RIGHT;
                        default:                    return -1;
                    }
                }
            });

            // 60 FPS game loop
            new Timer(1000 / 60, e -> {
                ppu.clearFrameComplete();
                while (!ppu.isFrameComplete()) ppu.tick();
                screen.updateFrame(ppu.getFrameBuffer());
            }).start();
        });
    }
}
