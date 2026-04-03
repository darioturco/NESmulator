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
┌─────────────┐
│   Screen    │  (Java Swing)
└─────────────┘
```

### Clock ratio
The PPU runs 3 times faster than the CPU:
```
ppu.tick(); ppu.tick(); ppu.tick();
cpu.tick();
```

## Project Structure

```
src/main/java/com/nes/
├── Main.java              Entry point, creates the window
├── NES.java               Orchestrator: holds and clocks all components
├── cpu/
│   ├── CPU.java           6502 CPU: registers, instructions, interrupts
│   └── Flags.java         Status register (P) flag bits
├── ppu/
│   └── PPU.java           Picture Processing Unit: tiles, sprites, frame buffer
└── memory/
    ├── Bus.java            Routes CPU address space to the right component
    ├── Cartridge.java      Loads .nes (iNES format), exposes PRG and CHR ROM
    └── Controller.java     8-button gamepad, mapped at $4016/$4017
```

## Building

```bash
mvn package
```

## Running

```bash
java -jar target/nes-emulator.jar path/to/rom.nes
```

## Supported Mappers

- **Mapper 0** (NROM) — covers most classic NES titles

## Testing the CPU

Use the `nestest.nes` ROM and compare the output log against the official `nestest.log` for cycle-accurate validation.
