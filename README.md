# NES Emulator

A Nintendo Entertainment System (NES) emulator written in Java 11, built with Maven.

## Architecture

```
┌─────────────┐       ┌─────────────────────────────────────────┐
│  Cartridge  │──────▶│                   Bus                    │
│  PRG-ROM    │       │  $0000-$1FFF  →  RAM (2KB, mirrored)    │
│  CHR-ROM    │       │  $2000-$3FFF  →  PPU Registers          │
└──────┬──────┘       │  $4000-$4017  →  APU / IO               │
       │              │  $4020-$FFFF  →  Cartridge (PRG-ROM)    │
       │ CHR          └────────────────────┬────────────────────┘
       │                                   │ read/write
       ▼                                   ▼
┌─────────────┐                    ┌──────────────┐
│     PPU     │◀───────────────────│     CPU      │
│  (2C02)     │  register access   │   (2A03)     │
│  256×240px  │  NMI on VBLANK     │  6502-based  │
└──────┬──────┘                    └──────────────┘
       │ frame buffer
       ▼
┌─────────────┐     ┌──────────────┐
│   Screen    │     │     APU      │
│ (Java Swing)│     │  (audio out) │
└─────────────┘     └──────────────┘
```

### Clock ratio
The PPU runs 3 times faster than the CPU:
```
ppu.tick(); ppu.tick(); ppu.tick();
cpu.tick();
apu.tick();
```

## Project Structure

```
src/main/java/com/nes/
├── Main.java              Entry point, game loop, input handling
├── NES.java               Orchestrator: holds and clocks all components
├── clock/
│   └── Clock.java         Frame pacing: sleep/busy-wait hybrid at 60 Hz
├── cpu/
│   ├── CPU.java           6502 CPU: registers, instructions, interrupts
│   └── Flags.java         Status register (P) flag bits
├── ppu/
│   ├── PPU.java           Picture Processing Unit: tiles, sprites, frame buffer
│   ├── PPUBus.java        PPU address space router
│   ├── NameTableMemory.java  2 KB internal VRAM with configurable mirroring
│   ├── PatternMemory.java    CHR pattern table access
│   └── PaletteMemory.java    32-byte palette RAM
├── apu/
│   ├── APU.java           Audio Processing Unit: all 5 channels, frame counter
│   └── AudioOutput.java   javax.sound SourceDataLine wrapper (44100 Hz, 16-bit)
└── memory/
    ├── Bus.java            Routes CPU address space to the right component
    ├── Cartridge.java      Loads .nes (iNES format), delegates to mapper
    ├── Controller.java     8-button gamepad, mapped at $4016/$4017
    └── mapper/
        ├── Mapper.java         Common interface (cpuRead/Write, ppuRead/Write, scanline IRQ)
        ├── MapperNROM.java         Mapper  0 — no bank switching
        ├── MapperMMC1.java         Mapper  1 — serial shift register, dynamic mirroring, PRG-RAM
        ├── MapperUxROM.java        Mapper  2 — single PRG bank register, fixed last bank
        ├── MapperCNROM.java        Mapper  3 — CHR bank switching, fixed PRG
        ├── MapperMMC3.java         Mapper  4 — 8-way PRG/CHR banking, scanline IRQ, PRG-RAM
        ├── MapperAxROM.java        Mapper  7 — 32 KB PRG bank switch + single-screen mirroring
        ├── MapperMMC2.java         Mapper  9 — CHR latch mechanism, fixed last-3 PRG banks
        ├── MapperColorDreams.java  Mapper 11 — combined PRG (bits 3-0) + CHR (bits 7-4) register
        └── MapperGxROM.java        Mapper 66 — combined PRG (bits 5-4) + CHR (bits 1-0) register
```

## Building & Running

```bash
# Compile and run
mvn -Dexec.args="path/to/rom.nes"

# Run tests
mvn test
```

**Controls:**

| NES Button  | Keyboard (P1) | Keyboard (P2) |
|-------------|---------------|---------------|
| D-Pad       | Arrow keys    | WASD          |
| A           | Z             | J             |
| B           | X             | K             |
| Start       | Enter         | T             |
| Select      | Right Shift   | Y             |
| Mute        | M             | —             |
| Save state  | F5            | —             |
| Load state  | F8            | —             |

Save states are written to `save_states/<rom-name>_1.sav`.

## Supported Mappers

| # | Name   | Games                                                    | Status      |
|---|--------|----------------------------------------------------------|-------------|
| 0 | NROM   | Donkey Kong, Super Mario Bros., Excitebike, Galaga       | Implemented |
| 1 | MMC1   | The Legend of Zelda, Metroid, Mega Man 2                 | Implemented |
| 2 | UxROM  | Contra, Castlevania, Mega Man, Duck Tales                | Implemented |
| 3 | CNROM  | Paperboy, Gradius, Solomon's Key, Arkanoid               | Implemented |
| 4 | MMC3   | Super Mario Bros. 2/3, Kirby's Adventure, Mega Man 3–6  | Implemented |
|  7 | AxROM         | Battletoads, Wizards & Warriors, Cobra Triangle          | Implemented |
|  9 | MMC2          | Mike Tyson's Punch-Out!!, Punch-Out!!                    | Implemented |
| 11 | Color Dreams  | Spiritual Warfare, Bible Adventures, Exodus              | Implemented |
| 66 | GxROM         | Super Mario Bros. + Duck Hunt, Donkey Kong Classics      | Implemented |

## APU Channels

| Channel  | Type          | Status      |
|----------|---------------|-------------|
| Pulse 1  | Square wave   | Implemented |
| Pulse 2  | Square wave   | Implemented |
| Triangle | Triangle wave | Implemented |
| Noise    | LFSR noise    | Implemented |
| DMC      | Delta mod.    | Implemented |

Audio starts muted. Press **M** to toggle.

## To-Do

### Emulation Accuracy
- [ ] **Sprite 0 hit** — pixel-accurate collision detection between sprite 0 and background
- [ ] **Open bus behaviour** — reads from unmapped addresses should return last bus value
- [ ] **CPU unofficial opcodes** — full illegal/undocumented 6502 instruction set
- [ ] **MMC3 IRQ timing** — replace scanline approximation with cycle-accurate PPU A12 edge detection

### Features
- [ ] **Debug tools** — memory viewer, CPU step-by-step execution, breakpoints
- [ ] **ROM browser** — file picker dialog on startup instead of command-line argument
- [ ] **Rewind** — ring buffer of recent states for a few seconds of rewind

## References

- [NESDev Wiki](https://www.nesdev.org/wiki/Nesdev_Wiki) — primary reference for NES hardware documentation
- [javidx9 — NES Emulator series](https://www.youtube.com/watch?v=F8kx56OZQhg&list=PLrOv9FMX8xJHqMvSGB_9G9nZZ_4IgteYf&index=2) — video series on building a NES emulator from scratch
