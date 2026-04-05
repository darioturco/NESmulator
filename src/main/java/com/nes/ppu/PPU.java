package com.nes.ppu;

import com.nes.memory.Cartridge;

/**
 * Ricoh 2C02 Picture Processing Unit.
 *
 * Produces a 256×240 pixel frame. Runs at 3× the CPU clock speed.
 * Each frame = 262 scanlines × 341 cycles = 89,342 PPU cycles.
 *
 * The PPU has its own 14-bit address bus ({@link PPUBus}) connecting:
 *   – {@link PatternMemory}   ($0000–$1FFF)  CHR-ROM/RAM from the cartridge
 *   – {@link NameTableMemory} ($2000–$2FFF)  2KB internal nametable VRAM
 *   – {@link PaletteMemory}   ($3F00–$3F1F)  32-byte palette RAM
 *
 * Background rendering uses the "Loopy" internal registers (v, t, x, w) and
 * 16-bit shift registers to produce one pixel per cycle on visible scanlines.
 *
 * Scanline timing:
 *   0–239   Visible
 *   240     Post-render (idle)
 *   241–260 Vertical blank  (NMI fires at scanline 241, cycle 1)
 *   261     Pre-render      (scroll registers reloaded)
 */
public class PPU {

    public static final int SCREEN_WIDTH  = 256;
    public static final int SCREEN_HEIGHT = 240;

    // -------------------------------------------------------------------------
    // Position
    // -------------------------------------------------------------------------

    private int scanline; // 0–261
    private int cycle;    // 0–340

    // -------------------------------------------------------------------------
    // Buses and memory
    // -------------------------------------------------------------------------

    private final PPUBus ppuBus = new PPUBus();

    // Object Attribute Memory (64 sprites × 4 bytes each)
    private final byte[] oam = new byte[256];

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    private final int[] frameBuffer = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
    private boolean frameComplete;

    // -------------------------------------------------------------------------
    // Loopy internal registers
    //
    // Both v and t share the same 15-bit field layout:
    //   bit 14–12  fine Y scroll     (3 bits)
    //   bit 11     nametable Y       (1 bit)
    //   bit 10     nametable X       (1 bit)
    //   bit  9– 5  coarse Y scroll   (5 bits)
    //   bit  4– 0  coarse X scroll   (5 bits)
    // -------------------------------------------------------------------------

    private int     v;   // current VRAM address  (15-bit)
    private int     t;   // temporary VRAM address (15-bit)
    private int     x;   // fine X scroll          (3-bit)
    private boolean w;   // write toggle

    // -------------------------------------------------------------------------
    // CPU-visible PPU registers  ($2000–$2007)
    // -------------------------------------------------------------------------

    /** 0 = normal rendering, 1 = solid red, 2 = solid green, 3 = solid blue. */
    private int colorTest = 0;

    private int ctrl;       // PPUCTRL   $2000
    private int mask;       // PPUMASK   $2001
    private int status;     // PPUSTATUS $2002
    private int oamAddr;    // OAMADDR   $2003
    private int dataBuffer; // PPUDATA delayed-read latch

    // -------------------------------------------------------------------------
    // Background pipeline
    //
    // Every 8 cycles the PPU fetches one tile worth of data into four latches,
    // then shifts them into the 16-bit shift registers at the start of the next
    // 8-cycle group.  One bit is consumed per cycle to produce one pixel.
    // -------------------------------------------------------------------------

    private int bgShiftPatLo,  bgShiftPatHi;   // pattern bit-planes  (16-bit each)
    private int bgShiftAttrLo, bgShiftAttrHi;  // palette attribute   (16-bit each)

    private int bgNextTileId;   // nametable byte fetched this group
    private int bgNextTileAttr; // 2-bit palette index for this group
    private int bgNextTileLo;   // pattern plane-0 byte
    private int bgNextTileHi;   // pattern plane-1 byte

    // -------------------------------------------------------------------------
    // NMI callback
    // -------------------------------------------------------------------------

    private Runnable nmiCallback;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public PPU() {}

    public void reset() {
        ppuBus.reset();
        scanline = 0;
        cycle    = 0;
        v = 0; t = 0; x = 0; w = false;
        ctrl = 0; mask = 0; status = 0; oamAddr = 0; dataBuffer = 0;
        bgShiftPatLo  = 0; bgShiftPatHi  = 0;
        bgShiftAttrLo = 0; bgShiftAttrHi = 0;
        bgNextTileId  = 0; bgNextTileAttr = 0;
        bgNextTileLo  = 0; bgNextTileHi   = 0;
        frameComplete = false;
    }

    public void setCartridge(Cartridge cartridge) {
        ppuBus.setCartridge(cartridge);
    }

    public void setNmiCallback(Runnable nmiCallback) {
        this.nmiCallback = nmiCallback;
    }

    /** 0 = normal, 1 = solid red, 2 = solid green, 3 = solid blue. */
    public void setColorTest(int colorTest) { this.colorTest = colorTest; }
    public int  getColorTest()              { return colorTest; }

    // =========================================================================
    // Clock – one PPU cycle
    // =========================================================================

    /**
     * Advance the PPU by one clock cycle.
     *
     * Rendering operates on:
     *   – Scanlines  0–239  (visible)
     *   – Scanline   261    (pre-render: scroll registers restored from t)
     *
     * Cycle timeline within those scanlines:
     *   Cycle  0        : idle
     *   Cycles  1– 256  : pixel output + 8-cycle background fetch groups
     *   Cycle  256      : increment coarse Y
     *   Cycle  257      : copy X from t → v ; load shift registers
     *   Cycles 280–304  : (pre-render only) copy Y from t → v
     *   Cycles 321–336  : pre-fetch first two tiles of the next scanline
     *   Cycles 337–340  : dummy nametable fetches
     */
    public void tick() {
        boolean rendering = (mask & 0x18) != 0;

        // -----------------------------------------------------------------
        // Visible + pre-render scanlines
        // -----------------------------------------------------------------
        if (scanline < 240 || scanline == 261) {

            // Background fetch pipeline (visible period + next-line pre-fetch)
            if ((cycle >= 2 && cycle <= 257) || (cycle >= 321 && cycle <= 337)) {
                if (rendering) updateShifters();

                switch ((cycle - 1) & 7) {
                    case 0: loadShifters();              fetchNametable();  break;
                    case 2:                              fetchAttribute();  break;
                    case 4:                              fetchPatternLo();  break;
                    case 6:                              fetchPatternHi();  break;
                    case 7: if (rendering) incrementX(); break;
                }
            }

            if (cycle == 256 && rendering) incrementY();

            if (cycle == 257) {
                loadShifters();
                if (rendering) copyX();
            }

            // Dummy nametable fetches at end of scanline
            if (cycle == 338 || cycle == 340) fetchNametable();

            // Pre-render: restore vertical scroll from t
            if (scanline == 261 && rendering && cycle >= 280 && cycle <= 304) copyY();
        }

        // -----------------------------------------------------------------
        // VBlank start (scanline 241, cycle 1)
        // -----------------------------------------------------------------
        if (scanline == 241 && cycle == 1) {
            status        |= 0x80; // set VBlank flag
            frameComplete  = true;
            if ((ctrl & 0x80) != 0 && nmiCallback != null) nmiCallback.run();
        }

        // -----------------------------------------------------------------
        // Pre-render: clear status flags
        // -----------------------------------------------------------------
        if (scanline == 261 && cycle == 1) status &= ~0xE0;

        // -----------------------------------------------------------------
        // Pixel output (visible scanlines, cycles 1–256)
        // -----------------------------------------------------------------
        if (scanline < 240 && cycle >= 1 && cycle <= 256) renderPixel();

        // -----------------------------------------------------------------
        // Advance position
        // -----------------------------------------------------------------
        if (++cycle > 340) {
            cycle = 0;
            if (++scanline > 261) scanline = 0;
        }
    }

    // =========================================================================
    // CPU-facing register access  (routed from Bus at $2000–$2007)
    // =========================================================================

    /**
     * CPU reads a PPU register.
     *
     * @param reg address bits 2–0 (i.e. addr & 0x07)
     * @return register value [0, 255]
     */
    public int readRegister(int reg) {
        switch (reg & 7) {
            case 2: { // PPUSTATUS — reading clears bit 7 and the write toggle
                int data = (status & 0xE0) | (dataBuffer & 0x1F);
                status &= ~0x80;
                w = false;
                return data;
            }
            case 4: // OAMDATA
                return oam[oamAddr] & 0xFF;
            case 7: { // PPUDATA — one-cycle read delay (palette is immediate)
                int data = dataBuffer;
                dataBuffer = ppuBus.read(v);
                if ((v & 0x3FFF) >= 0x3F00) data = dataBuffer;
                v = (v + ((ctrl & 0x04) != 0 ? 32 : 1)) & 0x7FFF;
                return data & 0xFF;
            }
            default:
                return 0;
        }
    }

    /**
     * CPU writes a PPU register.
     *
     * @param reg  address bits 2–0
     * @param data byte value [0, 255]
     */
    public void writeRegister(int reg, int data) {
        data &= 0xFF;
        switch (reg & 7) {
            case 0: // PPUCTRL
                ctrl = data;
                // t bits 11–10 = ctrl bits 1–0 (nametable select)
                t = (t & ~0x0C00) | ((data & 0x03) << 10);
                break;
            case 1: // PPUMASK
                mask = data;
                break;
            case 3: // OAMADDR
                oamAddr = data;
                break;
            case 4: // OAMDATA
                oam[oamAddr] = (byte) data;
                oamAddr = (oamAddr + 1) & 0xFF;
                break;
            case 5: // PPUSCROLL — two writes (X then Y)
                if (!w) {
                    t = (t & ~0x001F) | (data >> 3);       // coarse X
                    x = data & 0x07;                        // fine X
                } else {
                    t = (t & ~0x73E0)
                        | ((data & 0x07) << 12)             // fine Y
                        | ((data & 0xF8) << 2);             // coarse Y
                }
                w = !w;
                break;
            case 6: // PPUADDR — two writes (high byte then low byte)
                if (!w) {
                    t = (t & 0x00FF) | ((data & 0x3F) << 8);
                } else {
                    t = (t & 0xFF00) | data;
                    v = t;
                }
                w = !w;
                break;
            case 7: // PPUDATA
                ppuBus.write(v, data);
                v = (v + ((ctrl & 0x04) != 0 ? 32 : 1)) & 0x7FFF;
                break;
        }
    }

    /** OAM DMA — CPU writes 256 bytes directly into OAM ($4014). */
    public void writeDma(byte[] page) {
        for (int i = 0; i < 256; i++) oam[(oamAddr + i) & 0xFF] = page[i];
    }

    // =========================================================================
    // Internal VRAM access
    // =========================================================================

    private int  readVram(int addr)          { return ppuBus.read(addr); }
    private void writeVram(int addr, int d)  { ppuBus.write(addr, d); }

    // =========================================================================
    // Background pipeline helpers
    // =========================================================================

    /** Shift all four background shift registers left by one bit. */
    private void updateShifters() {
        if ((mask & 0x08) != 0) {
            bgShiftPatLo   = (bgShiftPatLo  << 1) & 0xFFFF;
            bgShiftPatHi   = (bgShiftPatHi  << 1) & 0xFFFF;
            bgShiftAttrLo  = (bgShiftAttrLo << 1) & 0xFFFF;
            bgShiftAttrHi  = (bgShiftAttrHi << 1) & 0xFFFF;
        }
    }

    /** Load the next-tile latches into the lower 8 bits of the shift registers. */
    private void loadShifters() {
        bgShiftPatLo  = (bgShiftPatLo  & 0xFF00) | (bgNextTileLo & 0xFF);
        bgShiftPatHi  = (bgShiftPatHi  & 0xFF00) | (bgNextTileHi & 0xFF);
        bgShiftAttrLo = (bgShiftAttrLo & 0xFF00) | ((bgNextTileAttr & 1) != 0 ? 0xFF : 0x00);
        bgShiftAttrHi = (bgShiftAttrHi & 0xFF00) | ((bgNextTileAttr & 2) != 0 ? 0xFF : 0x00);
    }

    /** Fetch the nametable byte for the tile at the current v address. */
    private void fetchNametable() {
        bgNextTileId = ppuBus.read(0x2000 | (v & 0x0FFF));
    }

    /**
     * Fetch the attribute byte and extract the 2-bit palette index for
     * the current tile (determined by coarse X and Y in v).
     */
    private void fetchAttribute() {
        int addr = 0x23C0
                 | (v & 0x0C00)          // nametable select
                 | ((v >> 4) & 0x38)     // coarseY / 4  → bits 5–3
                 | ((v >> 2) & 0x07);    // coarseX / 4  → bits 2–0
        int attr = ppuBus.read(addr);
        if ((v & 0x0040) != 0) attr >>= 4; // bottom half of 32×32 block
        if ((v & 0x0002) != 0) attr >>= 2; // right  half of 32×32 block
        bgNextTileAttr = attr & 0x03;
    }

    /** Fetch the low bit-plane byte of the current background tile. */
    private void fetchPatternLo() {
        int base  = ((ctrl & 0x10) != 0) ? 0x1000 : 0x0000;
        int fineY = (v >> 12) & 0x07;
        bgNextTileLo = ppuBus.read(base + bgNextTileId * 16 + fineY);
    }

    /** Fetch the high bit-plane byte of the current background tile. */
    private void fetchPatternHi() {
        int base  = ((ctrl & 0x10) != 0) ? 0x1000 : 0x0000;
        int fineY = (v >> 12) & 0x07;
        bgNextTileHi = ppuBus.read(base + bgNextTileId * 16 + fineY + 8);
    }

    /**
     * Increment coarse X in v, wrapping into the horizontal nametable bit
     * when coarse X overflows (tile column 31 → 0).
     */
    private void incrementX() {
        if ((v & 0x001F) == 31) {
            v &= ~0x001F;  // coarse X = 0
            v ^=  0x0400;  // flip horizontal nametable
        } else {
            v++;
        }
    }

    /**
     * Increment fine Y in v.  When fine Y overflows (7 → 0), increments
     * coarse Y and handles the special row-29 nametable flip.
     */
    private void incrementY() {
        if ((v & 0x7000) != 0x7000) {
            v += 0x1000; // increment fine Y
        } else {
            v &= ~0x7000; // fine Y = 0
            int y = (v >> 5) & 0x1F;
            if      (y == 29) { y = 0; v ^= 0x0800; } // flip vertical nametable
            else if (y == 31) { y = 0; }               // out-of-range wrap
            else              { y++; }
            v = (v & ~0x03E0) | (y << 5);
        }
    }

    /** Copy horizontal position fields (coarse X + NT.X) from t into v. */
    private void copyX() {
        v = (v & ~0x041F) | (t & 0x041F);
    }

    /** Copy vertical position fields (fine Y + NT.Y + coarse Y) from t into v. */
    private void copyY() {
        v = (v & ~0x7BE0) | (t & 0x7BE0);
    }

    /**
     * Produce one background pixel at (cycle−1, scanline) and write it
     * to the frame buffer.
     */
    private void renderPixel() {
        int px = cycle - 1;
        int py = scanline;

        if (colorTest != 0) {
            int color = colorTest == 1 ? 0xFFFF0000 : colorTest == 2 ? 0xFF00FF00 : 0xFF0000FF;
            frameBuffer[py * SCREEN_WIDTH + px] = color;
            return;
        }

        int bgPixel = 0, bgPalette = 0;

        if ((mask & 0x08) != 0) {
            int mux = 0x8000 >> x;                              // fine-X selector bit
            int p0  = (bgShiftPatLo  & mux) != 0 ? 1 : 0;
            int p1  = (bgShiftPatHi  & mux) != 0 ? 1 : 0;
            bgPixel   = (p1 << 1) | p0;
            int a0  = (bgShiftAttrLo & mux) != 0 ? 1 : 0;
            int a1  = (bgShiftAttrHi & mux) != 0 ? 1 : 0;
            bgPalette = (a1 << 1) | a0;
        }

        // Colour index 0 always uses the universal background colour ($3F00)
        int palAddr    = 0x3F00 + (bgPixel == 0 ? 0 : bgPalette * 4 + bgPixel);
        int colorIdx   = ppuBus.read(palAddr);
        frameBuffer[py * SCREEN_WIDTH + px] = ppuBus.toArgb(colorIdx);
    }

    // =========================================================================
    // Output
    // =========================================================================

    /** Returns the completed frame buffer (SCREEN_WIDTH × SCREEN_HEIGHT ARGB ints). */
    public int[] getFrameBuffer() { return frameBuffer; }

    /** True once per frame when the PPU signals vertical blank. */
    public boolean isFrameComplete() { return frameComplete; }

    /** Clear the frame-complete flag (call after consuming the frame). */
    public void clearFrameComplete() { frameComplete = false; }

    // =========================================================================
    // Console demo
    // =========================================================================

    /**
     * Standalone demo: loads hand-crafted tile data, runs two frames through
     * the PPU, and prints the result as ASCII art.
     *
     * Run with:  mvn compile exec:java -Dexec.mainClass=com.nes.ppu.PPU
     */
    public static void main(String[] args) {

        // ----------------------------------------------------------------
        // 1. Minimal cartridge backed by flat CHR-RAM
        // ----------------------------------------------------------------
        final int[] chrRam = new int[0x2000]; // 8 KB CHR-RAM

        Cartridge cart = new Cartridge() {
            @Override public int         ppuRead (int a)       { return chrRam[a & 0x1FFF]; }
            @Override public void        ppuWrite(int a, int d){ chrRam[a & 0x1FFF] = d & 0xFF; }
            @Override public MirrorMode  getMirrorMode()       { return MirrorMode.VERTICAL; }
        };

        PPU ppu = new PPU();
        ppu.setCartridge(cart);
        ppu.reset();

        // ----------------------------------------------------------------
        // 2. Define four 8×8 tiles in CHR pattern table 0 ($0000)
        //
        //    Each tile = 16 bytes: plane-0 (8 B) then plane-1 (8 B).
        //    Pixel colour index = (plane-1 bit << 1) | plane-0 bit  → 0–3
        //
        //    Tile $00 – blank (default zeroes, colour 0 everywhere)
        //    Tile $01 – solid block           (colour 1 everywhere)
        //    Tile $02 – hollow box            (colour 1 on outline only)
        //    Tile $03 – checkerboard          (colour 1 on even pixels)
        //    Tile $04 – cross / plus          (colour 1 on centre row+col)
        // ----------------------------------------------------------------

        int[][] tileDefs = {
            /* $01 solid   */ { 0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
                                0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 },
            /* $02 box     */ { 0xFF,0x81,0x81,0x81,0x81,0x81,0x81,0xFF,
                                0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 },
            /* $03 checker */ { 0xAA,0x55,0xAA,0x55,0xAA,0x55,0xAA,0x55,
                                0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 },
            /* $04 cross   */ { 0x18,0x18,0x18,0xFF,0xFF,0x18,0x18,0x18,
                                0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 }
        };
        for (int t = 0; t < tileDefs.length; t++) {
            int base = (t + 1) * 16; // tiles start at index 1
            for (int b = 0; b < 16; b++) chrRam[base + b] = tileDefs[t][b];
        }

        // ----------------------------------------------------------------
        // 3. Write nametable ($2000–$23BF) and attribute ($23C0–$23FF)
        //    via PPUADDR / PPUDATA register writes.
        //    Rendering is off during setup so v just increments freely.
        // ----------------------------------------------------------------

        ppu.writeRegister(1, 0x00); // PPUMASK: rendering disabled during setup

        // Point v at $2000
        ppu.writeRegister(6, 0x20);
        ppu.writeRegister(6, 0x00);

        // 32 × 30 = 960 tile entries
        for (int row = 0; row < 30; row++) {
            for (int col = 0; col < 32; col++) {
                int tile;
                boolean border      = row == 0 || row == 29 || col == 0  || col == 31;
                boolean innerBorder = row == 1 || row == 28 || col == 1  || col == 30;
                if      (border)       tile = 0x01; // solid
                else if (innerBorder)  tile = 0x02; // hollow box
                else                   tile = ((row + col) & 1) == 0 ? 0x03 : 0x04;
                ppu.writeRegister(7, tile);
            }
        }

        // 64 attribute bytes (all palette 0)
        for (int i = 0; i < 64; i++) ppu.writeRegister(7, 0x00);

        // ----------------------------------------------------------------
        // 4. Write background palette 0 at $3F00
        // ----------------------------------------------------------------

        ppu.writeRegister(6, 0x3F);
        ppu.writeRegister(6, 0x00);

        ppu.writeRegister(7, 0x0F); // $3F00 universal BG  – black
        ppu.writeRegister(7, 0x20); // $3F01 colour 1      – white
        ppu.writeRegister(7, 0x10); // $3F02 colour 2      – light grey
        ppu.writeRegister(7, 0x00); // $3F03 colour 3      – dark grey

        // ----------------------------------------------------------------
        // 5. Reset scroll to NT0 (0, 0) and enable background rendering.
        //    PPUCTRL must be written AFTER the PPUADDR $3F00 writes because
        //    those writes set nametable-select bits in t; PPUCTRL clears them.
        // ----------------------------------------------------------------

        ppu.writeRegister(0, 0x00); // PPUCTRL: clears t bits 11–10 (NT0, BG pattern $0000)
        ppu.writeRegister(5, 0x00); // PPUSCROLL X = 0
        ppu.writeRegister(5, 0x00); // PPUSCROLL Y = 0
        ppu.writeRegister(1, 0x08); // PPUMASK: show background

        // ----------------------------------------------------------------
        // 6. Run two full frames.
        //    Frame 1 primes the pipeline (v is in an arbitrary state after
        //    the PPUDATA writes).  Frame 2 starts cleanly because the
        //    pre-render scanline at the end of frame 1 restores v from t.
        // ----------------------------------------------------------------

        while (!ppu.isFrameComplete()) ppu.tick();
        ppu.clearFrameComplete();
        while (!ppu.isFrameComplete()) ppu.tick();

        // ----------------------------------------------------------------
        // 7. Render frame buffer to the console as ASCII art.
        //    Sample every 4th pixel horizontally and every 4th scanline
        //    vertically → 64 columns × 60 rows.
        // ----------------------------------------------------------------

        int[]  fb      = ppu.getFrameBuffer();
        char[] SHADING = { ' ', '.', '+', '#' };

        String hBorder = "+" + "-".repeat(64) + "+";
        System.out.println(hBorder);

        for (int py = 0; py < SCREEN_HEIGHT; py += 4) {
            StringBuilder sb = new StringBuilder(66);
            sb.append('|');
            for (int px = 0; px < SCREEN_WIDTH; px += 4) {
                int argb       = fb[py * SCREEN_WIDTH + px];
                int brightness = ((argb >> 16 & 0xFF) + (argb >> 8 & 0xFF) + (argb & 0xFF)) / 3;
                sb.append(SHADING[brightness * (SHADING.length - 1) / 255]);
            }
            sb.append('|');
            System.out.println(sb);
        }

        System.out.println(hBorder);
    }
}
