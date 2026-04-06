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
 * Sprite rendering evaluates OAM at cycle 257 of each scanline (pipeline model:
 * sprites evaluated on scanline N are displayed on scanline N+1). Supports
 * 8×8 and 8×16 modes, horizontal/vertical flipping, palette, and priority.
 *
 * Scanline timing:
 *   0–239   Visible
 *   240     Post-render (idle)
 *   241–260 Vertical blank  (NMI fires at scanline 241, cycle 1)
 *   261     Pre-render      (scroll registers reloaded; sprites for scanline 0 fetched)
 *
 * Bug fixes applied vs. original:
 *   – Left-column clipping: PPUMASK bits 1/2 now suppress BG/sprite in px 0–7
 *   – Sprite 0 hit: correctly suppressed in clipped columns and at x=255
 *   – Unused secondary-OAM slots zeroed so stale data cannot bleed into rendering
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

    // Primary OAM (64 sprites × 4 bytes)
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
    // Sprite pipeline
    //
    // At cycle 257 of each rendering scanline the PPU evaluates OAM to find
    // up to 8 sprites that intersect scanline+1, then pre-fetches their
    // pattern bytes.  The results are consumed during scanline+1's pixel output.
    // -------------------------------------------------------------------------

    /** Secondary OAM: Y, tile, attributes, X for up to 8 selected sprites. */
    private final int[] sprY    = new int[8];
    private final int[] sprTile = new int[8];
    private final int[] sprAttr = new int[8];
    private final int[] sprX    = new int[8];

    /** Pre-fetched pattern bytes for the selected sprites (horizontal flip already applied). */
    private final int[] sprPatLo = new int[8];
    private final int[] sprPatHi = new int[8];

    /** Number of sprites selected for the next scanline (0–8). */
    private int sprCount;

    /**
     * True when OAM sprite 0 was placed in secondary OAM slot 0 during the
     * last evaluation.  Used to trigger sprite-0 hit detection.
     */
    private boolean sprite0Loaded;

    // -------------------------------------------------------------------------
    // NMI callback / cartridge (for scanline IRQ)
    // -------------------------------------------------------------------------

    private Runnable  nmiCallback;
    private Cartridge cartridge;

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
        sprCount      = 0; sprite0Loaded  = false;
        for (int i = 0; i < 8; i++) {
            sprY[i] = sprTile[i] = sprAttr[i] = sprX[i] = 0;
            sprPatLo[i] = sprPatHi[i] = 0;
        }
        frameComplete = false;
    }

    // =========================================================================
    // Save state
    // =========================================================================

    public com.nes.SaveState.PpuState captureState() {
        int[][] ntBanks = ppuBus.captureNametableBanks();
        int[]   palRam  = ppuBus.capturePaletteRam();
        return new com.nes.SaveState.PpuState(
            scanline, cycle,
            v, t, x, w,
            colorTest, ctrl, mask, status, oamAddr, dataBuffer,
            bgShiftPatLo, bgShiftPatHi, bgShiftAttrLo, bgShiftAttrHi,
            bgNextTileId, bgNextTileAttr, bgNextTileLo, bgNextTileHi,
            oam.clone(),
            sprY.clone(), sprTile.clone(), sprAttr.clone(), sprX.clone(),
            sprPatLo.clone(), sprPatHi.clone(),
            sprCount, sprite0Loaded, frameComplete,
            ntBanks, palRam
        );
    }

    public void restoreState(com.nes.SaveState.PpuState s) {
        scanline = s.scanline; cycle = s.cycle;
        v = s.v; t = s.t; x = s.fineX; w = s.w;
        colorTest = s.colorTest; ctrl = s.ctrl; mask = s.mask;
        status = s.status; oamAddr = s.oamAddr; dataBuffer = s.dataBuffer;
        bgShiftPatLo  = s.bgShiftPatLo;  bgShiftPatHi  = s.bgShiftPatHi;
        bgShiftAttrLo = s.bgShiftAttrLo; bgShiftAttrHi = s.bgShiftAttrHi;
        bgNextTileId  = s.bgNextTileId;  bgNextTileAttr = s.bgNextTileAttr;
        bgNextTileLo  = s.bgNextTileLo;  bgNextTileHi   = s.bgNextTileHi;
        System.arraycopy(s.oam, 0, oam, 0, oam.length);
        System.arraycopy(s.sprY,     0, sprY,     0, 8);
        System.arraycopy(s.sprTile,  0, sprTile,  0, 8);
        System.arraycopy(s.sprAttr,  0, sprAttr,  0, 8);
        System.arraycopy(s.sprX,     0, sprX,     0, 8);
        System.arraycopy(s.sprPatLo, 0, sprPatLo, 0, 8);
        System.arraycopy(s.sprPatHi, 0, sprPatHi, 0, 8);
        sprCount = s.sprCount; sprite0Loaded = s.sprite0Loaded; frameComplete = s.frameComplete;
        ppuBus.restoreNametableBanks(s.nametableBanks);
        ppuBus.restorePaletteRam(s.paletteRam);
    }

    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
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
     *   Cycle  257      : copy X from t → v ; sprite evaluation + fetch for next scanline
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
                // Sprite pipeline: evaluate OAM and fetch patterns for the
                // next scanline.  Pre-render (261) evaluates for scanline 0.
                evaluateSprites();
                fetchSprites();
            }

            // Dummy nametable fetches at end of scanline
            if (cycle == 338 || cycle == 340) fetchNametable();

            // Pre-render: restore vertical scroll from t
            if (scanline == 261 && rendering && cycle >= 280 && cycle <= 304) copyY();
        }

        // -----------------------------------------------------------------
        // Scanline IRQ (MMC3 and similar): tick at cycle 260 of visible
        // scanlines and the pre-render scanline.
        // -----------------------------------------------------------------
        if (cycle == 260 && (scanline < 240 || scanline == 261) && cartridge != null) {
            cartridge.tickScanline();
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
        // Pre-render: clear VBlank, sprite-0 hit, and sprite-overflow flags
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
                if ((v & 0x3FFF) >= 0x3F00) data = dataBuffer; // palette: no delay
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

    /** OAM DMA — CPU copies 256 bytes directly into OAM ($4014). */
    public void writeDma(byte[] page) {
        for (int i = 0; i < 256; i++) oam[(oamAddr + i) & 0xFF] = page[i];
    }

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

    // =========================================================================
    // Sprite pipeline helpers
    // =========================================================================

    /**
     * Scan all 64 OAM entries and fill secondary OAM with up to 8 sprites
     * that intersect the next scanline.
     *
     * The "next scanline" is {@code scanline + 1}, with pre-render (261)
     * wrapping to 0 so that scanline-0 sprites are prepared during pre-render.
     */
    private void evaluateSprites() {
        int nextLine    = (scanline == 261) ? 0 : scanline + 1;
        int spriteSize  = ((ctrl & 0x20) != 0) ? 16 : 8;
        sprCount        = 0;
        sprite0Loaded   = false;

        for (int i = 0; i < 64; i++) {
            int y    = oam[i * 4] & 0xFF;
            int diff = nextLine - y;
            if (diff < 0 || diff >= spriteSize) continue;

            if (sprCount < 8) {
                if (i == 0) sprite0Loaded = true;
                sprY   [sprCount] = y;
                sprTile[sprCount] = oam[i * 4 + 1] & 0xFF;
                sprAttr[sprCount] = oam[i * 4 + 2] & 0xFF;
                sprX   [sprCount] = oam[i * 4 + 3] & 0xFF;
                sprCount++;
            } else {
                status |= 0x20; // sprite overflow (bit 5 of PPUSTATUS)
                break;
            }
        }

        // Clear unused slots so stale data cannot bleed into rendering
        for (int i = sprCount; i < 8; i++) {
            sprY[i] = sprTile[i] = sprAttr[i] = 0xFF;
            sprX[i] = 0xFF;
        }
    }

    /**
     * Fetch the 8-bit pattern planes for each sprite in secondary OAM.
     * Horizontal flip is applied here so that rendering can read patterns
     * MSB-first without knowing the flip state.
     */
    private void fetchSprites() {
        int nextLine   = (scanline == 261) ? 0 : scanline + 1;
        int spriteSize = ((ctrl & 0x20) != 0) ? 16 : 8;

        for (int i = 0; i < sprCount; i++) {
            int row      = nextLine - sprY[i];        // row within the sprite (0–7 or 0–15)
            boolean flipV = (sprAttr[i] & 0x80) != 0;
            boolean flipH = (sprAttr[i] & 0x40) != 0;

            int tileAddr;
            if (spriteSize == 8) {
                // 8×8: pattern table selected by PPUCTRL bit 3
                int base = ((ctrl & 0x08) != 0) ? 0x1000 : 0x0000;
                if (flipV) row = 7 - row;
                tileAddr = base + sprTile[i] * 16 + row;
            } else {
                // 8×16: bit 0 of tile index selects pattern table; bits 7–1 give tile
                int base = (sprTile[i] & 0x01) != 0 ? 0x1000 : 0x0000;
                int tile = sprTile[i] & 0xFE;
                if (flipV) row = 15 - row;
                if (row >= 8) { tile++; row -= 8; } // second half of tall sprite
                tileAddr = base + tile * 16 + row;
            }

            int lo = ppuBus.read(tileAddr);
            int hi = ppuBus.read(tileAddr + 8);

            if (flipH) {
                lo = reverseBits(lo);
                hi = reverseBits(hi);
            }

            sprPatLo[i] = lo;
            sprPatHi[i] = hi;
        }

        // Zero unused slots — transparent pixels, no rendering effect
        for (int i = sprCount; i < 8; i++) {
            sprPatLo[i] = 0;
            sprPatHi[i] = 0;
        }
    }

    /** Reverse the 8 bits of a byte (used for horizontal sprite flip). */
    private static int reverseBits(int b) {
        b = ((b & 0xF0) >> 4) | ((b & 0x0F) << 4);
        b = ((b & 0xCC) >> 2) | ((b & 0x33) << 2);
        b = ((b & 0xAA) >> 1) | ((b & 0x55) << 1);
        return b & 0xFF;
    }

    // =========================================================================
    // Pixel output
    // =========================================================================

    /**
     * Produce one pixel at (cycle−1, scanline) and write it to the frame buffer.
     *
     * Priority rules:
     *   1. If both BG and sprite pixels are transparent → backdrop colour ($3F00)
     *   2. If only sprite is visible                   → sprite pixel
     *   3. If only BG is visible                       → BG pixel
     *   4. Both visible: sprite attribute bit 5 decides
     *        0 = sprite in front of BG
     *        1 = sprite behind BG (BG pixel shown, but sprite-0 hit still fires)
     */
    private void renderPixel() {
        int px = cycle - 1;
        int py = scanline;

        if (colorTest != 0) {
            int color = colorTest == 1 ? 0xFFFF0000
                      : colorTest == 2 ? 0xFF00FF00
                      :                  0xFF0000FF;
            frameBuffer[py * SCREEN_WIDTH + px] = color;
            return;
        }

        // ---- Background ------------------------------------------------
        // PPUMASK bit 1 (0x02): show BG in leftmost 8 pixels
        boolean bgEnabled = (mask & 0x08) != 0
                         && (px >= 8 || (mask & 0x02) != 0);

        int bgPixel = 0, bgPalette = 0;
        if (bgEnabled) {
            int mux = 0x8000 >> x;                          // fine-X selector bit
            int p0  = (bgShiftPatLo  & mux) != 0 ? 1 : 0;
            int p1  = (bgShiftPatHi  & mux) != 0 ? 1 : 0;
            bgPixel   = (p1 << 1) | p0;
            int a0  = (bgShiftAttrLo & mux) != 0 ? 1 : 0;
            int a1  = (bgShiftAttrHi & mux) != 0 ? 1 : 0;
            bgPalette = (a1 << 1) | a0;
        }

        // ---- Sprites ---------------------------------------------------
        // PPUMASK bit 2 (0x04): show sprites in leftmost 8 pixels
        boolean sprEnabled = (mask & 0x10) != 0
                          && (px >= 8 || (mask & 0x04) != 0);

        int  sprPixel    = 0;
        int  sprPalette  = 0;
        boolean sprBehindBg = false;

        if (sprEnabled) {
            for (int i = 0; i < sprCount; i++) {
                int offset = px - sprX[i]; // pixel offset within this sprite (0–7)
                if (offset < 0 || offset > 7) continue;

                // Patterns were pre-flipped horizontally; read MSB-first
                int bit = 7 - offset;
                int p0  = (sprPatLo[i] >> bit) & 1;
                int p1  = (sprPatHi[i] >> bit) & 1;
                int pixel = (p1 << 1) | p0;
                if (pixel == 0) continue; // transparent

                // Sprite-0 hit: fires when OAM sprite 0 and a BG pixel overlap.
                // Conditions per NESDev:
                //   – Both BG and sprite rendering must be enabled (already checked)
                //   – Neither pixel may be clipped (px < 8 with clip enabled)
                //   – Not at x = 255
                //   – bgPixel must also be non-zero
                if (i == 0 && sprite0Loaded && bgPixel != 0 && px != 255) {
                    boolean bgCol0Ok  = px >= 8 || (mask & 0x02) != 0;
                    boolean sprCol0Ok = px >= 8 || (mask & 0x04) != 0;
                    if (bgCol0Ok && sprCol0Ok) status |= 0x40;
                }

                sprPixel    = pixel;
                sprPalette  = (sprAttr[i] & 0x03) + 4; // sprite palettes 4–7
                sprBehindBg = (sprAttr[i] & 0x20) != 0;
                break; // first non-transparent sprite wins
            }
        }

        // ---- Priority and palette lookup --------------------------------
        int finalPixel, finalPalette;
        if (bgPixel == 0 && sprPixel == 0) {
            finalPixel   = 0;
            finalPalette = 0;
        } else if (bgPixel == 0) {
            finalPixel   = sprPixel;
            finalPalette = sprPalette;
        } else if (sprPixel == 0) {
            finalPixel   = bgPixel;
            finalPalette = bgPalette;
        } else if (!sprBehindBg) {
            finalPixel   = sprPixel;   // sprite in front of BG
            finalPalette = sprPalette;
        } else {
            finalPixel   = bgPixel;    // sprite behind BG
            finalPalette = bgPalette;
        }

        // Colour index 0 always uses the universal background colour ($3F00)
        int palAddr  = 0x3F00 + (finalPixel == 0 ? 0 : finalPalette * 4 + finalPixel);
        int colorIdx = ppuBus.read(palAddr);
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

        final int[] chrRam = new int[0x2000];

        Cartridge cart = new Cartridge() {
            @Override public int         ppuRead (int a)       { return chrRam[a & 0x1FFF]; }
            @Override public void        ppuWrite(int a, int d){ chrRam[a & 0x1FFF] = d & 0xFF; }
            @Override public MirrorMode  getMirrorMode()       { return MirrorMode.VERTICAL; }
        };

        PPU ppu = new PPU();
        ppu.setCartridge(cart);
        ppu.reset();

        int[][] tileDefs = {
            { 0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF, 0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 },
            { 0xFF,0x81,0x81,0x81,0x81,0x81,0x81,0xFF, 0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 },
            { 0xAA,0x55,0xAA,0x55,0xAA,0x55,0xAA,0x55, 0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 },
            { 0x18,0x18,0x18,0xFF,0xFF,0x18,0x18,0x18, 0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 }
        };
        for (int t = 0; t < tileDefs.length; t++) {
            int base = (t + 1) * 16;
            for (int b = 0; b < 16; b++) chrRam[base + b] = tileDefs[t][b];
        }

        ppu.writeRegister(1, 0x00);
        ppu.writeRegister(6, 0x20); ppu.writeRegister(6, 0x00);
        for (int row = 0; row < 30; row++) {
            for (int col = 0; col < 32; col++) {
                boolean border      = row == 0 || row == 29 || col == 0 || col == 31;
                boolean innerBorder = row == 1 || row == 28 || col == 1 || col == 30;
                int tile = border ? 0x01 : innerBorder ? 0x02
                         : ((row + col) & 1) == 0 ? 0x03 : 0x04;
                ppu.writeRegister(7, tile);
            }
        }
        for (int i = 0; i < 64; i++) ppu.writeRegister(7, 0x00);
        ppu.writeRegister(6, 0x3F); ppu.writeRegister(6, 0x00);
        ppu.writeRegister(7, 0x0F);
        ppu.writeRegister(7, 0x20);
        ppu.writeRegister(7, 0x10);
        ppu.writeRegister(7, 0x00);
        ppu.writeRegister(0, 0x00);
        ppu.writeRegister(5, 0x00); ppu.writeRegister(5, 0x00);
        ppu.writeRegister(1, 0x1E); // BG + sprites enabled

        while (!ppu.isFrameComplete()) ppu.tick();
        ppu.clearFrameComplete();
        while (!ppu.isFrameComplete()) ppu.tick();

        int[]  fb      = ppu.getFrameBuffer();
        char[] SHADING = { ' ', '.', '+', '#' };
        String border  = "+" + "-".repeat(64) + "+";
        System.out.println(border);
        for (int py = 0; py < SCREEN_HEIGHT; py += 4) {
            StringBuilder sb = new StringBuilder(66).append('|');
            for (int px2 = 0; px2 < SCREEN_WIDTH; px2 += 4) {
                int argb       = fb[py * SCREEN_WIDTH + px2];
                int brightness = ((argb >> 16 & 0xFF) + (argb >> 8 & 0xFF) + (argb & 0xFF)) / 3;
                sb.append(SHADING[brightness * (SHADING.length - 1) / 255]);
            }
            System.out.println(sb.append('|'));
        }
        System.out.println(border);
    }
}
