package com.nes.memory.mapper;

/**
 * Common interface for all NES memory mappers.
 *
 * A mapper translates CPU and PPU addresses into offsets within the
 * cartridge's PRG-ROM/RAM and CHR-ROM/RAM arrays.  Each mapper subclass
 * implements its own bank-switching logic while this interface provides
 * the uniform contract used by {@link com.nes.memory.Cartridge}.
 *
 * CPU address space handled by mappers: $4020–$FFFF
 *   $6000–$7FFF  PRG-RAM (battery-backed save RAM, optional)
 *   $8000–$FFFF  PRG-ROM (one or more switchable 16 KB banks)
 *
 * PPU address space handled by mappers: $0000–$1FFF
 *   $0000–$0FFF  Pattern Table 0
 *   $1000–$1FFF  Pattern Table 1
 */
public interface Mapper {

    /**
     * CPU read from cartridge space.
     *
     * @param addr CPU address in $4020–$FFFF
     * @return byte value [0, 255], or 0 if unmapped
     */
    int cpuRead(int addr);

    /**
     * CPU write to cartridge space (mapper registers or PRG-RAM).
     *
     * @param addr CPU address in $4020–$FFFF
     * @param data byte value [0, 255]
     */
    void cpuWrite(int addr, int data);

    /**
     * PPU read from pattern table space.
     *
     * @param addr PPU address in $0000–$1FFF
     * @return byte value [0, 255]
     */
    int ppuRead(int addr);

    /**
     * PPU write to pattern table space (only meaningful for CHR-RAM).
     *
     * @param addr PPU address in $0000–$1FFF
     * @param data byte value [0, 255]
     */
    void ppuWrite(int addr, int data);
}
