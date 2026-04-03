package com.nes.cpu;

import com.nes.memory.Bus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the 2A03 / 6502 CPU instruction set.
 *
 * Each test loads a small program into a flat 64KB test bus, calls reset()
 * to initialise registers and point PC at the program, then uses step() to
 * execute individual instructions and asserts observable state.
 */
class CPUTest {

    /** Base address where test programs are loaded. */
    private static final int PROG = 0xC000;

    private TestBus bus;
    private CPU cpu;

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    /** Flat 64KB bus with no side-effects — just raw memory reads/writes. */
    private static class TestBus extends Bus {
        private final int[] mem = new int[0x10000];

        TestBus() { super(null); } // Bus.read/write are stubs; null PPU is safe

        @Override public int read(int addr)               { return mem[addr & 0xFFFF]; }
        @Override public void write(int addr, int data)   { mem[addr & 0xFFFF] = data & 0xFF; }

        /** Write a sequence of bytes starting at addr. */
        void load(int addr, int... bytes) {
            for (int i = 0; i < bytes.length; i++) mem[(addr + i) & 0xFFFF] = bytes[i] & 0xFF;
        }

        int readByte(int addr) { return mem[addr & 0xFFFF]; }
    }

    @BeforeEach
    void setUp() {
        bus = new TestBus();
        cpu = new CPU(bus);
        // Point reset vector at PROG
        bus.load(0xFFFC, PROG & 0xFF, (PROG >> 8) & 0xFF);
        cpu.reset();
    }

    /** Execute n instructions via step(). */
    private void step(int n) { for (int i = 0; i < n; i++) cpu.step(); }

    private boolean flag(int f) { return (cpu.getP() & f) != 0; }

    // =========================================================================
    // Load / Store
    // =========================================================================

    @Test void lda_imm_loads_value() {
        bus.load(PROG, 0xA9, 0x42);   // LDA #$42
        step(1);
        assertEquals(0x42, cpu.getA());
        assertFalse(flag(Flags.Z));
        assertFalse(flag(Flags.N));
    }

    @Test void lda_imm_sets_zero_flag() {
        bus.load(PROG, 0xA9, 0x00);   // LDA #$00
        step(1);
        assertTrue(flag(Flags.Z));
        assertFalse(flag(Flags.N));
    }

    @Test void lda_imm_sets_negative_flag() {
        bus.load(PROG, 0xA9, 0xFF);   // LDA #$FF
        step(1);
        assertTrue(flag(Flags.N));
        assertFalse(flag(Flags.Z));
    }

    @Test void lda_zpg_reads_from_zero_page() {
        bus.load(0x0010, 0x37);        // mem[$10] = $37
        bus.load(PROG,   0xA5, 0x10); // LDA $10
        step(1);
        assertEquals(0x37, cpu.getA());
    }

    @Test void ldx_imm_loads_value() {
        bus.load(PROG, 0xA2, 0x55);   // LDX #$55
        step(1);
        assertEquals(0x55, cpu.getX());
    }

    @Test void ldy_imm_loads_value() {
        bus.load(PROG, 0xA0, 0x99);   // LDY #$99
        step(1);
        assertEquals(0x99, cpu.getY());
    }

    @Test void sta_zpg_writes_accumulator() {
        bus.load(PROG, 0xA9, 0xAB,    // LDA #$AB
                       0x85, 0x20);   // STA $20
        step(2);
        assertEquals(0xAB, bus.readByte(0x20));
    }

    @Test void stx_zpg_writes_x() {
        bus.load(PROG, 0xA2, 0x11,    // LDX #$11
                       0x86, 0x30);   // STX $30
        step(2);
        assertEquals(0x11, bus.readByte(0x30));
    }

    @Test void sty_zpg_writes_y() {
        bus.load(PROG, 0xA0, 0x22,    // LDY #$22
                       0x84, 0x40);   // STY $40
        step(2);
        assertEquals(0x22, bus.readByte(0x40));
    }

    // =========================================================================
    // Register Transfers
    // =========================================================================

    @Test void tax_copies_a_to_x_and_sets_flags() {
        bus.load(PROG, 0xA9, 0x80,    // LDA #$80
                       0xAA);         // TAX
        step(2);
        assertEquals(0x80, cpu.getX());
        assertTrue(flag(Flags.N));
    }

    @Test void txa_copies_x_to_a() {
        bus.load(PROG, 0xA2, 0x07,    // LDX #$07
                       0x8A);         // TXA
        step(2);
        assertEquals(0x07, cpu.getA());
    }

    @Test void tay_copies_a_to_y() {
        bus.load(PROG, 0xA9, 0x33,    // LDA #$33
                       0xA8);         // TAY
        step(2);
        assertEquals(0x33, cpu.getY());
    }

    @Test void tya_copies_y_to_a() {
        bus.load(PROG, 0xA0, 0x44,    // LDY #$44
                       0x98);         // TYA
        step(2);
        assertEquals(0x44, cpu.getA());
    }

    @Test void txs_sets_stack_pointer() {
        bus.load(PROG, 0xA2, 0x80,    // LDX #$80
                       0x9A);         // TXS
        step(2);
        assertEquals(0x80, cpu.getSP());
    }

    @Test void tsx_copies_sp_to_x_and_sets_flags() {
        // After reset SP = $FD
        bus.load(PROG, 0xBA);         // TSX
        step(1);
        assertEquals(0xFD, cpu.getX());
        assertTrue(flag(Flags.N));    // $FD has bit 7 set
    }

    // =========================================================================
    // Stack
    // =========================================================================

    @Test void pha_push_pla_pull_roundtrip() {
        bus.load(PROG, 0xA9, 0x5A,    // LDA #$5A
                       0x48,          // PHA
                       0xA9, 0x00,    // LDA #$00  (overwrite A)
                       0x68);         // PLA
        step(4);
        assertEquals(0x5A, cpu.getA());
    }

    @Test void php_sets_b_and_u_bits_on_stack() {
        bus.load(PROG, 0x08);         // PHP
        int spBefore = cpu.getSP();
        step(1);
        int pushed = bus.readByte(0x0100 | ((spBefore) & 0xFF));
        assertTrue((pushed & Flags.B) != 0);
        assertTrue((pushed & Flags.U) != 0);
    }

    @Test void plp_restores_status_clears_b() {
        // Push a specific status byte, then pull it
        bus.load(PROG, 0xA9, 0b11001111,  // LDA #$CF  (value to push via PHA then use as P)
                       0x48,              // PHA
                       0x28);             // PLP
        step(3);
        // B flag should be masked out; U should be set
        assertFalse(flag(Flags.B));
        assertTrue(flag(Flags.U));
    }

    // =========================================================================
    // Logic
    // =========================================================================

    @Test void and_masks_accumulator() {
        bus.load(PROG, 0xA9, 0xFF,    // LDA #$FF
                       0x29, 0x0F);   // AND #$0F
        step(2);
        assertEquals(0x0F, cpu.getA());
    }

    @Test void and_sets_zero_flag() {
        bus.load(PROG, 0xA9, 0xF0,    // LDA #$F0
                       0x29, 0x0F);   // AND #$0F
        step(2);
        assertEquals(0x00, cpu.getA());
        assertTrue(flag(Flags.Z));
    }

    @Test void ora_sets_bits() {
        bus.load(PROG, 0xA9, 0x0F,    // LDA #$0F
                       0x09, 0xF0);   // ORA #$F0
        step(2);
        assertEquals(0xFF, cpu.getA());
    }

    @Test void eor_flips_bits() {
        bus.load(PROG, 0xA9, 0xFF,    // LDA #$FF
                       0x49, 0xAA);   // EOR #$AA
        step(2);
        assertEquals(0x55, cpu.getA());
    }

    @Test void bit_sets_n_v_from_memory_and_z_from_and() {
        bus.load(0x50, 0b11000000);    // mem[$50] = $C0  (bits 7 and 6 set)
        bus.load(PROG, 0xA9, 0x00,    // LDA #$00
                       0x24, 0x50);   // BIT $50
        step(2);
        assertTrue(flag(Flags.Z));
        assertTrue(flag(Flags.N));
        assertTrue(flag(Flags.V));
    }

    @Test void bit_clears_z_when_masked_result_nonzero() {
        bus.load(0x50, 0xFF);
        bus.load(PROG, 0xA9, 0xFF,    // LDA #$FF
                       0x24, 0x50);   // BIT $50
        step(2);
        assertFalse(flag(Flags.Z));
    }

    // =========================================================================
    // Arithmetic — ADC
    // =========================================================================

    @Test void adc_no_carry_in_no_carry_out() {
        bus.load(PROG, 0x18,          // CLC
                       0xA9, 0x10,    // LDA #$10
                       0x69, 0x20);   // ADC #$20
        step(3);
        assertEquals(0x30, cpu.getA());
        assertFalse(flag(Flags.C));
        assertFalse(flag(Flags.V));
    }

    @Test void adc_produces_carry() {
        bus.load(PROG, 0x18,          // CLC
                       0xA9, 0xFF,    // LDA #$FF
                       0x69, 0x01);   // ADC #$01
        step(3);
        assertEquals(0x00, cpu.getA());
        assertTrue(flag(Flags.C));
        assertTrue(flag(Flags.Z));
    }

    @Test void adc_carry_in_adds_one() {
        bus.load(PROG, 0x38,          // SEC
                       0xA9, 0x10,    // LDA #$10
                       0x69, 0x10);   // ADC #$10
        step(3);
        assertEquals(0x21, cpu.getA());
    }

    @Test void adc_signed_overflow() {
        // $50 + $50 = $A0 → positive + positive = negative ⇒ overflow
        bus.load(PROG, 0x18,
                       0xA9, 0x50,
                       0x69, 0x50);
        step(3);
        assertEquals(0xA0, cpu.getA());
        assertTrue(flag(Flags.V));
        assertFalse(flag(Flags.C));
    }

    // =========================================================================
    // Arithmetic — SBC
    // =========================================================================

    @Test void sbc_basic_subtraction() {
        // $50 - $10 = $40, carry set (no borrow), no overflow
        bus.load(PROG, 0x38,          // SEC  (C=1 means no borrow)
                       0xA9, 0x50,    // LDA #$50
                       0xE9, 0x10);   // SBC #$10
        step(3);
        assertEquals(0x40, cpu.getA());
        assertTrue(flag(Flags.C));
        assertFalse(flag(Flags.V));
    }

    @Test void sbc_borrow_clears_carry() {
        // $10 - $50: result is negative, borrow occurs → C cleared
        bus.load(PROG, 0x38,
                       0xA9, 0x10,
                       0xE9, 0x50);
        step(3);
        assertEquals(0xC0, cpu.getA());
        assertFalse(flag(Flags.C));
    }

    @Test void sbc_signed_overflow() {
        // $50 - $B0 = $50 - (-$50) = +160 → overflow
        bus.load(PROG, 0x38,
                       0xA9, 0x50,
                       0xE9, 0xB0);
        step(3);
        assertTrue(flag(Flags.V));
    }

    // =========================================================================
    // Compare
    // =========================================================================

    @Test void cmp_equal_sets_z_and_c() {
        bus.load(PROG, 0xA9, 0x42,    // LDA #$42
                       0xC9, 0x42);   // CMP #$42
        step(2);
        assertTrue(flag(Flags.Z));
        assertTrue(flag(Flags.C));
        assertFalse(flag(Flags.N));
    }

    @Test void cmp_greater_sets_c_clears_z() {
        bus.load(PROG, 0xA9, 0x50,    // LDA #$50
                       0xC9, 0x10);   // CMP #$10
        step(2);
        assertFalse(flag(Flags.Z));
        assertTrue(flag(Flags.C));
    }

    @Test void cmp_less_clears_c() {
        bus.load(PROG, 0xA9, 0x10,
                       0xC9, 0x50);
        step(2);
        assertFalse(flag(Flags.C));
    }

    @Test void cpx_equal() {
        bus.load(PROG, 0xA2, 0x42,    // LDX #$42
                       0xE0, 0x42);   // CPX #$42
        step(2);
        assertTrue(flag(Flags.Z));
        assertTrue(flag(Flags.C));
    }

    @Test void cpy_equal() {
        bus.load(PROG, 0xA0, 0x42,    // LDY #$42
                       0xC0, 0x42);   // CPY #$42
        step(2);
        assertTrue(flag(Flags.Z));
        assertTrue(flag(Flags.C));
    }

    // =========================================================================
    // Increment / Decrement
    // =========================================================================

    @Test void inx_increments_x() {
        bus.load(PROG, 0xA2, 0x0F,    // LDX #$0F
                       0xE8);         // INX
        step(2);
        assertEquals(0x10, cpu.getX());
    }

    @Test void inx_wraps_from_ff_to_00() {
        bus.load(PROG, 0xA2, 0xFF,
                       0xE8);
        step(2);
        assertEquals(0x00, cpu.getX());
        assertTrue(flag(Flags.Z));
    }

    @Test void dex_decrements_x() {
        bus.load(PROG, 0xA2, 0x01,
                       0xCA);         // DEX
        step(2);
        assertEquals(0x00, cpu.getX());
        assertTrue(flag(Flags.Z));
    }

    @Test void iny_increments_y() {
        bus.load(PROG, 0xA0, 0x05,
                       0xC8);         // INY
        step(2);
        assertEquals(0x06, cpu.getY());
    }

    @Test void dey_decrements_y() {
        bus.load(PROG, 0xA0, 0x01,
                       0x88);         // DEY
        step(2);
        assertEquals(0x00, cpu.getY());
    }

    @Test void inc_memory() {
        bus.load(0x60, 0x09);
        bus.load(PROG, 0xE6, 0x60);   // INC $60
        step(1);
        assertEquals(0x0A, bus.readByte(0x60));
    }

    @Test void dec_memory() {
        bus.load(0x60, 0x01);
        bus.load(PROG, 0xC6, 0x60);   // DEC $60
        step(1);
        assertEquals(0x00, bus.readByte(0x60));
        assertTrue(flag(Flags.Z));
    }

    // =========================================================================
    // Shifts — Accumulator mode
    // =========================================================================

    @Test void asl_acc_shifts_left_and_sets_carry() {
        bus.load(PROG, 0xA9, 0b10000001,  // LDA #$81
                       0x0A);             // ASL A
        step(2);
        assertEquals(0b00000010, cpu.getA());
        assertTrue(flag(Flags.C));
    }

    @Test void lsr_acc_shifts_right_and_sets_carry() {
        bus.load(PROG, 0xA9, 0b00000011,  // LDA #$03
                       0x4A);             // LSR A
        step(2);
        assertEquals(0b00000001, cpu.getA());
        assertTrue(flag(Flags.C));
    }

    @Test void rol_acc_rotates_carry_in() {
        bus.load(PROG, 0x38,              // SEC
                       0xA9, 0b00000000,  // LDA #$00
                       0x2A);             // ROL A
        step(3);
        assertEquals(0b00000001, cpu.getA());
        assertFalse(flag(Flags.C));
    }

    @Test void ror_acc_rotates_carry_in() {
        bus.load(PROG, 0x38,              // SEC
                       0xA9, 0b00000000,  // LDA #$00
                       0x6A);             // ROR A
        step(3);
        assertEquals(0b10000000, cpu.getA());
        assertFalse(flag(Flags.C));
    }

    // =========================================================================
    // Shifts — Memory mode
    // =========================================================================

    @Test void asl_zpg_shifts_memory() {
        bus.load(0x70, 0x40);
        bus.load(PROG, 0x06, 0x70);    // ASL $70
        step(1);
        assertEquals(0x80, bus.readByte(0x70));
        assertFalse(flag(Flags.C));
        assertTrue(flag(Flags.N));
    }

    @Test void lsr_zpg_shifts_memory() {
        bus.load(0x70, 0x02);
        bus.load(PROG, 0x46, 0x70);    // LSR $70
        step(1);
        assertEquals(0x01, bus.readByte(0x70));
    }

    @Test void rol_zpg_rotates_memory() {
        bus.load(0x70, 0x80);
        bus.load(PROG, 0x18,           // CLC
                       0x26, 0x70);    // ROL $70
        step(2);
        assertEquals(0x00, bus.readByte(0x70));
        assertTrue(flag(Flags.C));     // old bit 7 → C
    }

    @Test void ror_zpg_rotates_memory() {
        bus.load(0x70, 0x01);
        bus.load(PROG, 0x18,           // CLC
                       0x66, 0x70);    // ROR $70
        step(2);
        assertEquals(0x00, bus.readByte(0x70));
        assertTrue(flag(Flags.C));     // old bit 0 → C
    }

    // =========================================================================
    // Jumps
    // =========================================================================

    @Test void jmp_absolute_sets_pc() {
        bus.load(PROG, 0x4C, 0x00, 0xD0);  // JMP $D000
        step(1);
        assertEquals(0xD000, cpu.getPC());
    }

    @Test void jmp_indirect_follows_pointer() {
        bus.load(0x0200, 0x00, 0xD0);       // pointer at $0200 → $D000
        bus.load(PROG,   0x6C, 0x00, 0x02); // JMP ($0200)
        step(1);
        assertEquals(0xD000, cpu.getPC());
    }

    @Test void jsr_pushes_return_addr_and_jumps() {
        bus.load(PROG, 0x20, 0x00, 0xD0);  // JSR $D000
        int spBefore = cpu.getSP();
        step(1);
        assertEquals(0xD000, cpu.getPC());
        // Stack should hold PROG+2 (last byte of JSR instruction)
        int hi = bus.readByte(0x0100 | spBefore);
        int lo = bus.readByte(0x0100 | ((spBefore - 1) & 0xFF));
        int pushed = (hi << 8) | lo;
        assertEquals(PROG + 2, pushed);
    }

    @Test void rts_returns_after_jsr() {
        // JSR to a RTS — should return to the instruction after JSR
        int sub = 0xD000;
        bus.load(sub,  0x60);              // RTS
        bus.load(PROG, 0x20, sub & 0xFF, (sub >> 8) & 0xFF,  // JSR $D000
                       0xEA);             // NOP  ← we should land here
        step(2); // JSR + RTS
        assertEquals(PROG + 3, cpu.getPC());
    }

    // =========================================================================
    // BRK and RTI
    // =========================================================================

    @Test void brk_vectors_to_irq_handler_and_rti_returns() {
        int handler = 0xE000;
        bus.load(0xFFFE, handler & 0xFF, (handler >> 8) & 0xFF); // IRQ vector
        bus.load(handler, 0x40);          // RTI
        bus.load(PROG,    0x00,           // BRK
                          0xFF);          // padding byte (skipped by BRK)
        int pcAfterBrk = PROG + 2;       // RTI should return here

        step(1); // BRK → jumps to handler
        assertEquals(handler, cpu.getPC());
        assertTrue(flag(Flags.I));

        step(1); // RTI → returns
        assertEquals(pcAfterBrk, cpu.getPC());
    }

    // =========================================================================
    // Branches
    // =========================================================================

    @Test void beq_not_taken_when_z_clear() {
        bus.load(PROG, 0xA9, 0x01,      // LDA #$01  (Z=0)
                       0xF0, 0x10);     // BEQ +$10
        step(2);
        assertEquals(PROG + 4, cpu.getPC()); // PC just advances past branch
    }

    @Test void beq_taken_when_z_set() {
        bus.load(PROG, 0xA9, 0x00,      // LDA #$00  (Z=1)
                       0xF0, 0x04);     // BEQ +$04
        step(2);
        // PC after reading opcode+offset = PROG+4; branch adds 4 → PROG+8
        assertEquals(PROG + 8, cpu.getPC());
    }

    @Test void bne_taken_when_z_clear() {
        bus.load(PROG, 0xA9, 0x01,      // LDA #$01  (Z=0)
                       0xD0, 0x02);     // BNE +$02
        step(2);
        assertEquals(PROG + 6, cpu.getPC());
    }

    @Test void bcc_taken_when_carry_clear() {
        bus.load(PROG, 0x18,            // CLC
                       0x90, 0x02);     // BCC +$02
        step(2);
        assertEquals(PROG + 5, cpu.getPC());
    }

    @Test void bcs_taken_when_carry_set() {
        bus.load(PROG, 0x38,            // SEC
                       0xB0, 0x02);     // BCS +$02
        step(2);
        assertEquals(PROG + 5, cpu.getPC());
    }

    @Test void bmi_taken_when_negative_set() {
        bus.load(PROG, 0xA9, 0x80,      // LDA #$80  (N=1)
                       0x30, 0x02);     // BMI +$02
        step(2);
        assertEquals(PROG + 6, cpu.getPC());
    }

    @Test void bpl_taken_when_negative_clear() {
        bus.load(PROG, 0xA9, 0x01,      // LDA #$01  (N=0)
                       0x10, 0x02);     // BPL +$02
        step(2);
        assertEquals(PROG + 6, cpu.getPC());
    }

    @Test void bvc_taken_when_overflow_clear() {
        bus.load(PROG, 0xB8,            // CLV
                       0x50, 0x02);     // BVC +$02
        step(2);
        assertEquals(PROG + 5, cpu.getPC());
    }

    @Test void bvs_taken_when_overflow_set() {
        // Force overflow: $50 + $50 = $A0 sets V
        bus.load(PROG, 0x18,
                       0xA9, 0x50,
                       0x69, 0x50,     // ADC #$50  → V=1
                       0x70, 0x02);    // BVS +$02
        step(4);
        assertEquals(PROG + 9, cpu.getPC());
    }

    @Test void branch_backward() {
        int target = PROG + 0x02;       // back 2 bytes from branch
        // BNE with offset = $FE (-2 signed) → target = (PROG+4)+(-2) = PROG+2
        bus.load(PROG, 0xA9, 0x01,      // LDA #$01
                       0xD0, 0xFE);     // BNE -2  (infinite loop — step just once)
        step(2);
        assertEquals(PROG + 2, cpu.getPC());
    }

    // =========================================================================
    // Flag instructions
    // =========================================================================

    @Test void clc_clears_carry() {
        bus.load(PROG, 0x38, 0x18);     // SEC then CLC
        step(2);
        assertFalse(flag(Flags.C));
    }

    @Test void sec_sets_carry() {
        bus.load(PROG, 0x38);           // SEC
        step(1);
        assertTrue(flag(Flags.C));
    }

    @Test void cli_clears_interrupt_disable() {
        bus.load(PROG, 0x58);           // CLI
        step(1);
        assertFalse(flag(Flags.I));
    }

    @Test void sei_sets_interrupt_disable() {
        bus.load(PROG, 0x58, 0x78);     // CLI then SEI
        step(2);
        assertTrue(flag(Flags.I));
    }

    @Test void clv_clears_overflow() {
        bus.load(PROG, 0x18,
                       0xA9, 0x50,
                       0x69, 0x50,     // ADC → V=1
                       0xB8);          // CLV
        step(4);
        assertFalse(flag(Flags.V));
    }

    @Test void cld_clears_decimal() {
        bus.load(PROG, 0xF8, 0xD8);    // SED then CLD
        step(2);
        assertFalse(flag(Flags.D));
    }

    @Test void sed_sets_decimal() {
        bus.load(PROG, 0xF8);          // SED
        step(1);
        assertTrue(flag(Flags.D));
    }

    // =========================================================================
    // NOP
    // =========================================================================

    @Test void nop_advances_pc_by_one() {
        bus.load(PROG, 0xEA);          // NOP
        int pcBefore = cpu.getPC();
        step(1);
        assertEquals(pcBefore + 1, cpu.getPC());
    }

    // =========================================================================
    // NMI
    // =========================================================================

    @Test void nmi_vectors_to_handler_and_sets_i_flag() {
        int handler = 0xF000;
        bus.load(0xFFFA, handler & 0xFF, (handler >> 8) & 0xFF);
        bus.load(handler, 0xEA);       // NOP (handler body)
        bus.load(PROG,    0xEA);       // NOP (main program)

        step(1);                       // execute one NOP at PROG
        int pcAfterNop = cpu.getPC();
        cpu.nmi();                     // trigger NMI

        assertEquals(handler, cpu.getPC());
        assertTrue(flag(Flags.I));

        step(1);                       // NOP in handler
        assertEquals(handler + 1, cpu.getPC());
    }

    // =========================================================================
    // Invalid opcode — should skip the byte and continue
    // =========================================================================

    @Test void invalid_opcode_skips_to_next_instruction() {
        // $02 is an invalid opcode; $EA is NOP
        bus.load(PROG, 0x02, 0xEA);    // invalid, then NOP
        int pcBefore = cpu.getPC();
        step(1);                       // executes the invalid byte (treated as NOP)
        assertEquals(pcBefore + 1, cpu.getPC());
        step(1);                       // NOP
        assertEquals(pcBefore + 2, cpu.getPC());
    }
}
