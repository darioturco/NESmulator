package com.nes.cpu;

import com.nes.memory.Bus;

/**
 * Ricoh 2A03 CPU — MOS 6502 variant (decimal mode disabled).
 *
 * Execution model: on the first cycle of each instruction the full operation is
 * performed and the remaining cycle counter is loaded. Subsequent ticks simply
 * drain that counter, giving other components the correct number of cycles to
 * work with without requiring cycle-exact internal state.
 *
 * All byte values are stored as int and masked with 0xFF.
 * All addresses are stored as int and masked with 0xFFFF.
 */
public class CPU {

    // ---- Registers ----
    private int a;      // Accumulator   (8-bit)
    private int x;      // Index X       (8-bit)
    private int y;      // Index Y       (8-bit)
    private int sp;     // Stack pointer (8-bit, stack at $0100–$01FF)
    private int pc;     // Program counter (16-bit)
    private int p;      // Status flags  (8-bit, see Flags)

    /** Remaining cycles for the instruction currently in flight. */
    private int cycles;

    /** Set by indexed addressing modes when a page boundary is crossed. */
    private boolean pageCrossed;

    private final Bus bus;

    // -------------------------------------------------------------------------
    // Opcode table types
    // -------------------------------------------------------------------------

    /** Resolves an effective address (or -1 for accumulator) and may set pageCrossed. */
    @FunctionalInterface
    private interface AddrMode { int fetch(); }

    /** Executes an instruction and returns any extra cycles (e.g. page-cross penalty). */
    @FunctionalInterface
    private interface Instr { int exec(int addr); }

    private static final class Op {
        final int cycles;
        final AddrMode mode;
        final Instr instr;
        Op(int cycles, AddrMode mode, Instr instr) {
            this.cycles = cycles;
            this.mode   = mode;
            this.instr  = instr;
        }
    }

    private final Op[] ops = new Op[256];

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public CPU(Bus bus) {
        this.bus = bus;
        buildOpcodeTable();
    }

    // -------------------------------------------------------------------------
    // Interrupts
    // -------------------------------------------------------------------------

    /** Execute the RESET sequence: registers initialised, PC loaded from $FFFC/$FFFD. */
    public void reset() {
        a  = 0;
        x  = 0;
        y  = 0;
        sp = 0xFD;
        p  = Flags.U | Flags.I;
        pc = readWord(0xFFFC);
        cycles = 8;
    }

    /** Non-Maskable Interrupt – called by the PPU at the start of VBLANK. */
    public void nmi() {
        pushWord(pc);
        push((p | Flags.U) & ~Flags.B);
        setFlag(Flags.I, true);
        pc = readWord(0xFFFA);
        cycles = 8;
    }

    /** Maskable Interrupt Request – ignored when the I flag is set. */
    public void irq() {
        if (getFlag(Flags.I)) return;
        pushWord(pc);
        push((p | Flags.U) & ~Flags.B);
        setFlag(Flags.I, true);
        pc = readWord(0xFFFE);
        cycles = 7;
    }

    // -------------------------------------------------------------------------
    // Clock
    // -------------------------------------------------------------------------
    // Save state
    // -------------------------------------------------------------------------

    public com.nes.SaveState.CpuState captureState() {
        return new com.nes.SaveState.CpuState(a, x, y, sp, pc, p, cycles, pageCrossed);
    }

    public void restoreState(com.nes.SaveState.CpuState s) {
        a = s.a; x = s.x; y = s.y; sp = s.sp; pc = s.pc; p = s.p;
        cycles = s.cycles; pageCrossed = s.pageCrossed;
    }

    // -------------------------------------------------------------------------

    /**
     * Advance the CPU by one clock cycle.
     * When the remaining cycle count reaches zero the next instruction is
     * fetched, decoded, and fully executed; its cycle count is then loaded.
     */
    public void tick() {
        if (cycles == 0) {
            int opcode = read(pc) & 0xFF;
            pc = (pc + 1) & 0xFFFF;
            Op op = ops[opcode];
            pageCrossed = false;
            int addr  = op.mode.fetch();
            cycles    = op.cycles + op.instr.exec(addr);
        }
        cycles--;
    }

    // -------------------------------------------------------------------------
    // Memory access
    // -------------------------------------------------------------------------

    private int read(int addr) {
        return bus.read(addr & 0xFFFF) & 0xFF;
    }

    private void write(int addr, int data) {
        bus.write(addr & 0xFFFF, data & 0xFF);
    }

    /** Read a 16-bit little-endian word from the bus. */
    private int readWord(int addr) {
        return read(addr) | (read((addr + 1) & 0xFFFF) << 8);
    }

    // -------------------------------------------------------------------------
    // Stack helpers  (stack lives at $0100–$01FF)
    // -------------------------------------------------------------------------

    private void push(int data) {
        write(0x0100 | sp, data & 0xFF);
        sp = (sp - 1) & 0xFF;
    }

    private int pop() {
        sp = (sp + 1) & 0xFF;
        return read(0x0100 | sp);
    }

    private void pushWord(int data) {
        push((data >> 8) & 0xFF);
        push(data & 0xFF);
    }

    private int popWord() {
        int lo = pop();
        int hi = pop();
        return (hi << 8) | lo;
    }

    // -------------------------------------------------------------------------
    // Flag helpers
    // -------------------------------------------------------------------------

    private void setFlag(int flag, boolean value) {
        if (value) p |= flag; else p &= ~flag;
    }

    private boolean getFlag(int flag) {
        return (p & flag) != 0;
    }

    /** Update the Zero and Negative flags based on an 8-bit result. */
    private void setZN(int val) {
        setFlag(Flags.Z, (val & 0xFF) == 0);
        setFlag(Flags.N, (val & 0x80) != 0);
    }

    // -------------------------------------------------------------------------
    // Addressing modes
    // -------------------------------------------------------------------------

    /** Implied – no operand needed. */
    private int imp() { return 0; }

    /** Accumulator – sentinel value; instructions check for this and use 'a'. */
    private int acc() { return -1; }

    /** Immediate – operand is the next byte in the instruction stream. */
    private int imm() {
        int addr = pc;
        pc = (pc + 1) & 0xFFFF;
        return addr;
    }

    /** Zero Page – one-byte address, upper byte always $00. */
    private int zp() {
        int addr = read(pc) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return addr;
    }

    /** Zero Page, X – zero-page address + X, wraps within page 0. */
    private int zpx() {
        int addr = (read(pc) + x) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return addr;
    }

    /** Zero Page, Y – zero-page address + Y, wraps within page 0. */
    private int zpy() {
        int addr = (read(pc) + y) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return addr;
    }

    /** Absolute – full 16-bit address in the next two bytes (little-endian). */
    private int abs_() {
        int addr = readWord(pc);
        pc = (pc + 2) & 0xFFFF;
        return addr;
    }

    /** Absolute, X – absolute address + X; sets pageCrossed if page boundary crossed. */
    private int abx() {
        int base = readWord(pc);
        pc = (pc + 2) & 0xFFFF;
        int addr = (base + x) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (addr & 0xFF00);
        return addr;
    }

    /** Absolute, Y – absolute address + Y; sets pageCrossed if page boundary crossed. */
    private int aby() {
        int base = readWord(pc);
        pc = (pc + 2) & 0xFFFF;
        int addr = (base + y) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (addr & 0xFF00);
        return addr;
    }

    /**
     * Indirect – operand is a pointer; reads the target address from that pointer.
     * Reproduces the 6502 page-wrap bug: if the pointer is at $xxFF the high byte
     * is fetched from $xx00 instead of $xx+1:00.
     */
    private int ind() {
        int ptr = readWord(pc);
        pc = (pc + 2) & 0xFFFF;
        int lo = read(ptr);
        int hi = read((ptr & 0xFF00) | ((ptr + 1) & 0x00FF)); // page-wrap bug
        return (hi << 8) | lo;
    }

    /** (Indirect, X) – zero-page pointer + X; reads the 16-bit target from that address. */
    private int izx() {
        int base = (read(pc) + x) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        int lo = read(base);
        int hi = read((base + 1) & 0xFF);
        return (hi << 8) | lo;
    }

    /**
     * (Indirect), Y – reads a 16-bit base from the zero-page pointer, then adds Y.
     * Sets pageCrossed if the final address crosses a page boundary.
     */
    private int izy() {
        int ptr  = read(pc) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        int lo   = read(ptr);
        int hi   = read((ptr + 1) & 0xFF);
        int base = (hi << 8) | lo;
        int addr = (base + y) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (addr & 0xFF00);
        return addr;
    }

    /**
     * Relative – reads a signed one-byte offset used by branch instructions.
     * Returns the raw signed offset; branch handlers add it to PC themselves.
     */
    private int rel() {
        int offset = read(pc);
        pc = (pc + 1) & 0xFFFF;
        if ((offset & 0x80) != 0) offset |= 0xFFFFFF00; // sign-extend
        return offset;
    }

    // -------------------------------------------------------------------------
    // Instructions
    // -------------------------------------------------------------------------

    // ---- Load / Store -------------------------------------------------------

    private int LDA(int addr) { a = read(addr); setZN(a); return pageCrossed ? 1 : 0; }
    private int LDX(int addr) { x = read(addr); setZN(x); return pageCrossed ? 1 : 0; }
    private int LDY(int addr) { y = read(addr); setZN(y); return pageCrossed ? 1 : 0; }
    private int STA(int addr) { write(addr, a); return 0; }
    private int STX(int addr) { write(addr, x); return 0; }
    private int STY(int addr) { write(addr, y); return 0; }

    // ---- Register Transfers -------------------------------------------------

    private int TAX(int addr) { x  = a;  setZN(x);  return 0; }
    private int TXA(int addr) { a  = x;  setZN(a);  return 0; }
    private int TAY(int addr) { y  = a;  setZN(y);  return 0; }
    private int TYA(int addr) { a  = y;  setZN(a);  return 0; }
    private int TSX(int addr) { x  = sp; setZN(x);  return 0; }
    private int TXS(int addr) { sp = x;             return 0; }

    // ---- Stack --------------------------------------------------------------

    private int PHA(int addr) { push(a); return 0; }
    private int PLA(int addr) { a = pop(); setZN(a); return 0; }
    /** PHP always pushes with B and U set (hardware behaviour). */
    private int PHP(int addr) { push(p | Flags.B | Flags.U); return 0; }
    /** PLP clears B (it is not a real flag) and always sets U. */
    private int PLP(int addr) { p = (pop() & ~Flags.B) | Flags.U; return 0; }

    // ---- Logical ------------------------------------------------------------

    private int AND(int addr) { a &= read(addr); setZN(a); return pageCrossed ? 1 : 0; }
    private int ORA(int addr) { a |= read(addr); setZN(a); return pageCrossed ? 1 : 0; }
    private int EOR(int addr) { a ^= read(addr); setZN(a); return pageCrossed ? 1 : 0; }

    /** BIT: tests bits in memory against A; N/V copied from bits 7/6 of the value. */
    private int BIT(int addr) {
        int val = read(addr);
        setFlag(Flags.Z, (a & val) == 0);
        setFlag(Flags.N, (val & 0x80) != 0);
        setFlag(Flags.V, (val & 0x40) != 0);
        return 0;
    }

    // ---- Arithmetic ---------------------------------------------------------

    /** ADC: A = A + M + C  (binary mode only; decimal mode not implemented on 2A03). */
    private int ADC(int addr) {
        int val    = read(addr);
        int result = a + val + (getFlag(Flags.C) ? 1 : 0);
        setFlag(Flags.C, result > 0xFF);
        setFlag(Flags.V, ((~(a ^ val)) & (a ^ result) & 0x80) != 0);
        a = result & 0xFF;
        setZN(a);
        return pageCrossed ? 1 : 0;
    }

    /** SBC: A = A - M - (1 - C)  implemented as ADC with M inverted. */
    private int SBC(int addr) {
        int val    = read(addr) ^ 0xFF; // ~M in 8 bits
        int result = a + val + (getFlag(Flags.C) ? 1 : 0);
        setFlag(Flags.C, result > 0xFF);
        setFlag(Flags.V, ((a ^ result) & (val ^ result) & 0x80) != 0);
        a = result & 0xFF;
        setZN(a);
        return pageCrossed ? 1 : 0;
    }

    // ---- Compare ------------------------------------------------------------

    private int compare(int reg, int addr) {
        int val = read(addr);
        setFlag(Flags.C, reg >= val);
        setFlag(Flags.Z, reg == val);
        setFlag(Flags.N, ((reg - val) & 0x80) != 0);
        return pageCrossed ? 1 : 0;
    }

    private int CMP(int addr) { return compare(a, addr); }
    private int CPX(int addr) { return compare(x, addr); }
    private int CPY(int addr) { return compare(y, addr); }

    // ---- Increment / Decrement ----------------------------------------------

    private int INC(int addr) { int v = (read(addr) + 1) & 0xFF; write(addr, v); setZN(v); return 0; }
    private int DEC(int addr) { int v = (read(addr) - 1) & 0xFF; write(addr, v); setZN(v); return 0; }
    private int INX(int addr) { x = (x + 1) & 0xFF; setZN(x); return 0; }
    private int INY(int addr) { y = (y + 1) & 0xFF; setZN(y); return 0; }
    private int DEX(int addr) { x = (x - 1) & 0xFF; setZN(x); return 0; }
    private int DEY(int addr) { y = (y - 1) & 0xFF; setZN(y); return 0; }

    // ---- Shifts / Rotates ---------------------------------------------------

    /** ASL: arithmetic shift left; bit 7 → C, 0 → bit 0. Operates on A when addr == -1. */
    private int ASL(int addr) {
        if (addr == -1) {
            setFlag(Flags.C, (a & 0x80) != 0);
            a = (a << 1) & 0xFF;
            setZN(a);
        } else {
            int val = read(addr);
            setFlag(Flags.C, (val & 0x80) != 0);
            val = (val << 1) & 0xFF;
            write(addr, val);
            setZN(val);
        }
        return 0;
    }

    /** LSR: logical shift right; bit 0 → C, 0 → bit 7. Operates on A when addr == -1. */
    private int LSR(int addr) {
        if (addr == -1) {
            setFlag(Flags.C, (a & 0x01) != 0);
            a = (a >> 1) & 0x7F;
            setZN(a);
        } else {
            int val = read(addr);
            setFlag(Flags.C, (val & 0x01) != 0);
            val = (val >> 1) & 0x7F;
            write(addr, val);
            setZN(val);
        }
        return 0;
    }

    /** ROL: rotate left through carry; C → bit 0, bit 7 → C. Operates on A when addr == -1. */
    private int ROL(int addr) {
        int carry = getFlag(Flags.C) ? 1 : 0;
        if (addr == -1) {
            setFlag(Flags.C, (a & 0x80) != 0);
            a = ((a << 1) | carry) & 0xFF;
            setZN(a);
        } else {
            int val = read(addr);
            setFlag(Flags.C, (val & 0x80) != 0);
            val = ((val << 1) | carry) & 0xFF;
            write(addr, val);
            setZN(val);
        }
        return 0;
    }

    /** ROR: rotate right through carry; C → bit 7, bit 0 → C. Operates on A when addr == -1. */
    private int ROR(int addr) {
        int carry = getFlag(Flags.C) ? 0x80 : 0;
        if (addr == -1) {
            setFlag(Flags.C, (a & 0x01) != 0);
            a = ((a >> 1) | carry) & 0xFF;
            setZN(a);
        } else {
            int val = read(addr);
            setFlag(Flags.C, (val & 0x01) != 0);
            val = ((val >> 1) | carry) & 0xFF;
            write(addr, val);
            setZN(val);
        }
        return 0;
    }

    // ---- Jumps / Calls / Returns --------------------------------------------

    private int JMP(int addr) { pc = addr; return 0; }

    /** JSR: push (PC - 1) then jump; (PC - 1) is the last byte of the JSR instruction. */
    private int JSR(int addr) {
        pushWord((pc - 1) & 0xFFFF);
        pc = addr;
        return 0;
    }

    /** RTS: pop return address and add 1 (restores PC to the instruction after JSR). */
    private int RTS(int addr) {
        pc = (popWord() + 1) & 0xFFFF;
        return 0;
    }

    /** RTI: restore P then PC from the stack (used to return from interrupt handlers). */
    private int RTI(int addr) {
        p  = (pop() & ~Flags.B) | Flags.U;
        pc = popWord();
        return 0;
    }

    /**
     * BRK: software interrupt.
     * Pushes PC + 1 (skipping the padding byte that follows BRK in the stream),
     * then P with B and U set, then vectors through $FFFE/$FFFF.
     */
    private int BRK(int addr) {
        pushWord((pc + 1) & 0xFFFF); // pc already past opcode byte; +1 skips the padding byte
        push(p | Flags.B | Flags.U);
        setFlag(Flags.I, true);
        pc = readWord(0xFFFE);
        return 0;
    }

    // ---- Branches -----------------------------------------------------------

    /**
     * Common branch logic.
     * Returns 1 extra cycle if the branch is taken (same page),
     * or 2 extra cycles if it crosses a page boundary.
     */
    private int branch(boolean condition, int offset) {
        if (!condition) return 0;
        int target = (pc + offset) & 0xFFFF;
        int extra  = (pc & 0xFF00) != (target & 0xFF00) ? 2 : 1;
        pc = target;
        return extra;
    }

    private int BCC(int offset) { return branch(!getFlag(Flags.C), offset); }
    private int BCS(int offset) { return branch( getFlag(Flags.C), offset); }
    private int BEQ(int offset) { return branch( getFlag(Flags.Z), offset); }
    private int BNE(int offset) { return branch(!getFlag(Flags.Z), offset); }
    private int BMI(int offset) { return branch( getFlag(Flags.N), offset); }
    private int BPL(int offset) { return branch(!getFlag(Flags.N), offset); }
    private int BVC(int offset) { return branch(!getFlag(Flags.V), offset); }
    private int BVS(int offset) { return branch( getFlag(Flags.V), offset); }

    // ---- Flag instructions --------------------------------------------------

    private int CLC(int addr) { setFlag(Flags.C, false); return 0; }
    private int SEC(int addr) { setFlag(Flags.C, true);  return 0; }
    private int CLI(int addr) { setFlag(Flags.I, false); return 0; }
    private int SEI(int addr) { setFlag(Flags.I, true);  return 0; }
    private int CLD(int addr) { setFlag(Flags.D, false); return 0; }
    private int SED(int addr) { setFlag(Flags.D, true);  return 0; }
    private int CLV(int addr) { setFlag(Flags.V, false); return 0; }

    // ---- Other --------------------------------------------------------------

    private int NOP(int addr) { return 0; }

    // -------------------------------------------------------------------------
    // Opcode dispatch table
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Package-private accessors (used by tests)
    // -------------------------------------------------------------------------

    int getA()  { return a; }
    int getX()  { return x; }
    int getY()  { return y; }
    int getSP() { return sp; }
    int getPC() { return pc; }
    int getP()  { return p; }

    /**
     * Execute exactly one instruction at the current PC without cycle-by-cycle
     * counting. Intended for unit tests only.
     */
    void step() {
        int opcode = read(pc) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        Op op = ops[opcode];
        pageCrossed = false;
        int addr = op.mode.fetch();
        cycles = op.cycles + op.instr.exec(addr);
    }

    // -------------------------------------------------------------------------
    // Opcode dispatch table
    // -------------------------------------------------------------------------

    private void buildOpcodeTable() {
        // Default every slot to a 2-cycle NOP (handles illegal/unofficial opcodes gracefully)
        for (int i = 0; i < 256; i++) ops[i] = new Op(2, this::imp, this::NOP);

        // $00  BRK  impl   7
        ops[0x00] = new Op(7, this::imp,  this::BRK);
        // $01  ORA  (ind,X) 6
        ops[0x01] = new Op(6, this::izx,  this::ORA);
        // $05  ORA  zpg    3
        ops[0x05] = new Op(3, this::zp,   this::ORA);
        // $06  ASL  zpg    5
        ops[0x06] = new Op(5, this::zp,   this::ASL);
        // $08  PHP  impl   3
        ops[0x08] = new Op(3, this::imp,  this::PHP);
        // $09  ORA  imm    2
        ops[0x09] = new Op(2, this::imm,  this::ORA);
        // $0A  ASL  acc    2
        ops[0x0A] = new Op(2, this::acc,  this::ASL);
        // $0D  ORA  abs    4
        ops[0x0D] = new Op(4, this::abs_, this::ORA);
        // $0E  ASL  abs    6
        ops[0x0E] = new Op(6, this::abs_, this::ASL);

        // $10  BPL  rel    2+
        ops[0x10] = new Op(2, this::rel,  this::BPL);
        // $11  ORA  (ind),Y 5+
        ops[0x11] = new Op(5, this::izy,  this::ORA);
        // $15  ORA  zpg,X  4
        ops[0x15] = new Op(4, this::zpx,  this::ORA);
        // $16  ASL  zpg,X  6
        ops[0x16] = new Op(6, this::zpx,  this::ASL);
        // $18  CLC  impl   2
        ops[0x18] = new Op(2, this::imp,  this::CLC);
        // $19  ORA  abs,Y  4+
        ops[0x19] = new Op(4, this::aby,  this::ORA);
        // $1D  ORA  abs,X  4+
        ops[0x1D] = new Op(4, this::abx,  this::ORA);
        // $1E  ASL  abs,X  7
        ops[0x1E] = new Op(7, this::abx,  this::ASL);

        // $20  JSR  abs    6
        ops[0x20] = new Op(6, this::abs_, this::JSR);
        // $21  AND  (ind,X) 6
        ops[0x21] = new Op(6, this::izx,  this::AND);
        // $24  BIT  zpg    3
        ops[0x24] = new Op(3, this::zp,   this::BIT);
        // $25  AND  zpg    3
        ops[0x25] = new Op(3, this::zp,   this::AND);
        // $26  ROL  zpg    5
        ops[0x26] = new Op(5, this::zp,   this::ROL);
        // $28  PLP  impl   4
        ops[0x28] = new Op(4, this::imp,  this::PLP);
        // $29  AND  imm    2
        ops[0x29] = new Op(2, this::imm,  this::AND);
        // $2A  ROL  acc    2
        ops[0x2A] = new Op(2, this::acc,  this::ROL);
        // $2C  BIT  abs    4
        ops[0x2C] = new Op(4, this::abs_, this::BIT);
        // $2D  AND  abs    4
        ops[0x2D] = new Op(4, this::abs_, this::AND);
        // $2E  ROL  abs    6
        ops[0x2E] = new Op(6, this::abs_, this::ROL);

        // $30  BMI  rel    2+
        ops[0x30] = new Op(2, this::rel,  this::BMI);
        // $31  AND  (ind),Y 5+
        ops[0x31] = new Op(5, this::izy,  this::AND);
        // $35  AND  zpg,X  4
        ops[0x35] = new Op(4, this::zpx,  this::AND);
        // $36  ROL  zpg,X  6
        ops[0x36] = new Op(6, this::zpx,  this::ROL);
        // $38  SEC  impl   2
        ops[0x38] = new Op(2, this::imp,  this::SEC);
        // $39  AND  abs,Y  4+
        ops[0x39] = new Op(4, this::aby,  this::AND);
        // $3D  AND  abs,X  4+
        ops[0x3D] = new Op(4, this::abx,  this::AND);
        // $3E  ROL  abs,X  7
        ops[0x3E] = new Op(7, this::abx,  this::ROL);

        // $40  RTI  impl   6
        ops[0x40] = new Op(6, this::imp,  this::RTI);
        // $41  EOR  (ind,X) 6
        ops[0x41] = new Op(6, this::izx,  this::EOR);
        // $45  EOR  zpg    3
        ops[0x45] = new Op(3, this::zp,   this::EOR);
        // $46  LSR  zpg    5
        ops[0x46] = new Op(5, this::zp,   this::LSR);
        // $48  PHA  impl   3
        ops[0x48] = new Op(3, this::imp,  this::PHA);
        // $49  EOR  imm    2
        ops[0x49] = new Op(2, this::imm,  this::EOR);
        // $4A  LSR  acc    2
        ops[0x4A] = new Op(2, this::acc,  this::LSR);
        // $4C  JMP  abs    3
        ops[0x4C] = new Op(3, this::abs_, this::JMP);
        // $4D  EOR  abs    4
        ops[0x4D] = new Op(4, this::abs_, this::EOR);
        // $4E  LSR  abs    6
        ops[0x4E] = new Op(6, this::abs_, this::LSR);

        // $50  BVC  rel    2+
        ops[0x50] = new Op(2, this::rel,  this::BVC);
        // $51  EOR  (ind),Y 5+
        ops[0x51] = new Op(5, this::izy,  this::EOR);
        // $55  EOR  zpg,X  4
        ops[0x55] = new Op(4, this::zpx,  this::EOR);
        // $56  LSR  zpg,X  6
        ops[0x56] = new Op(6, this::zpx,  this::LSR);
        // $58  CLI  impl   2
        ops[0x58] = new Op(2, this::imp,  this::CLI);
        // $59  EOR  abs,Y  4+
        ops[0x59] = new Op(4, this::aby,  this::EOR);
        // $5D  EOR  abs,X  4+
        ops[0x5D] = new Op(4, this::abx,  this::EOR);
        // $5E  LSR  abs,X  7
        ops[0x5E] = new Op(7, this::abx,  this::LSR);

        // $60  RTS  impl   6
        ops[0x60] = new Op(6, this::imp,  this::RTS);
        // $61  ADC  (ind,X) 6
        ops[0x61] = new Op(6, this::izx,  this::ADC);
        // $65  ADC  zpg    3
        ops[0x65] = new Op(3, this::zp,   this::ADC);
        // $66  ROR  zpg    5
        ops[0x66] = new Op(5, this::zp,   this::ROR);
        // $68  PLA  impl   4
        ops[0x68] = new Op(4, this::imp,  this::PLA);
        // $69  ADC  imm    2
        ops[0x69] = new Op(2, this::imm,  this::ADC);
        // $6A  ROR  acc    2
        ops[0x6A] = new Op(2, this::acc,  this::ROR);
        // $6C  JMP  ind    5
        ops[0x6C] = new Op(5, this::ind,  this::JMP);
        // $6D  ADC  abs    4
        ops[0x6D] = new Op(4, this::abs_, this::ADC);
        // $6E  ROR  abs    6
        ops[0x6E] = new Op(6, this::abs_, this::ROR);

        // $70  BVS  rel    2+
        ops[0x70] = new Op(2, this::rel,  this::BVS);
        // $71  ADC  (ind),Y 5+
        ops[0x71] = new Op(5, this::izy,  this::ADC);
        // $75  ADC  zpg,X  4
        ops[0x75] = new Op(4, this::zpx,  this::ADC);
        // $76  ROR  zpg,X  6
        ops[0x76] = new Op(6, this::zpx,  this::ROR);
        // $78  SEI  impl   2
        ops[0x78] = new Op(2, this::imp,  this::SEI);
        // $79  ADC  abs,Y  4+
        ops[0x79] = new Op(4, this::aby,  this::ADC);
        // $7D  ADC  abs,X  4+
        ops[0x7D] = new Op(4, this::abx,  this::ADC);
        // $7E  ROR  abs,X  7
        ops[0x7E] = new Op(7, this::abx,  this::ROR);

        // $81  STA  (ind,X) 6
        ops[0x81] = new Op(6, this::izx,  this::STA);
        // $84  STY  zpg    3
        ops[0x84] = new Op(3, this::zp,   this::STY);
        // $85  STA  zpg    3
        ops[0x85] = new Op(3, this::zp,   this::STA);
        // $86  STX  zpg    3
        ops[0x86] = new Op(3, this::zp,   this::STX);
        // $88  DEY  impl   2
        ops[0x88] = new Op(2, this::imp,  this::DEY);
        // $8A  TXA  impl   2
        ops[0x8A] = new Op(2, this::imp,  this::TXA);
        // $8C  STY  abs    4
        ops[0x8C] = new Op(4, this::abs_, this::STY);
        // $8D  STA  abs    4
        ops[0x8D] = new Op(4, this::abs_, this::STA);
        // $8E  STX  abs    4
        ops[0x8E] = new Op(4, this::abs_, this::STX);

        // $90  BCC  rel    2+
        ops[0x90] = new Op(2, this::rel,  this::BCC);
        // $91  STA  (ind),Y 6  (no page-cross penalty for stores)
        ops[0x91] = new Op(6, this::izy,  this::STA);
        // $94  STY  zpg,X  4
        ops[0x94] = new Op(4, this::zpx,  this::STY);
        // $95  STA  zpg,X  4
        ops[0x95] = new Op(4, this::zpx,  this::STA);
        // $96  STX  zpg,Y  4
        ops[0x96] = new Op(4, this::zpy,  this::STX);
        // $98  TYA  impl   2
        ops[0x98] = new Op(2, this::imp,  this::TYA);
        // $99  STA  abs,Y  5  (no page-cross penalty for stores)
        ops[0x99] = new Op(5, this::aby,  this::STA);
        // $9A  TXS  impl   2
        ops[0x9A] = new Op(2, this::imp,  this::TXS);
        // $9D  STA  abs,X  5  (no page-cross penalty for stores)
        ops[0x9D] = new Op(5, this::abx,  this::STA);

        // $A0  LDY  imm    2
        ops[0xA0] = new Op(2, this::imm,  this::LDY);
        // $A1  LDA  (ind,X) 6
        ops[0xA1] = new Op(6, this::izx,  this::LDA);
        // $A2  LDX  imm    2
        ops[0xA2] = new Op(2, this::imm,  this::LDX);
        // $A4  LDY  zpg    3
        ops[0xA4] = new Op(3, this::zp,   this::LDY);
        // $A5  LDA  zpg    3
        ops[0xA5] = new Op(3, this::zp,   this::LDA);
        // $A6  LDX  zpg    3
        ops[0xA6] = new Op(3, this::zp,   this::LDX);
        // $A8  TAY  impl   2
        ops[0xA8] = new Op(2, this::imp,  this::TAY);
        // $A9  LDA  imm    2
        ops[0xA9] = new Op(2, this::imm,  this::LDA);
        // $AA  TAX  impl   2
        ops[0xAA] = new Op(2, this::imp,  this::TAX);
        // $AC  LDY  abs    4
        ops[0xAC] = new Op(4, this::abs_, this::LDY);
        // $AD  LDA  abs    4
        ops[0xAD] = new Op(4, this::abs_, this::LDA);
        // $AE  LDX  abs    4
        ops[0xAE] = new Op(4, this::abs_, this::LDX);

        // $B0  BCS  rel    2+
        ops[0xB0] = new Op(2, this::rel,  this::BCS);
        // $B1  LDA  (ind),Y 5+
        ops[0xB1] = new Op(5, this::izy,  this::LDA);
        // $B4  LDY  zpg,X  4
        ops[0xB4] = new Op(4, this::zpx,  this::LDY);
        // $B5  LDA  zpg,X  4
        ops[0xB5] = new Op(4, this::zpx,  this::LDA);
        // $B6  LDX  zpg,Y  4
        ops[0xB6] = new Op(4, this::zpy,  this::LDX);
        // $B8  CLV  impl   2
        ops[0xB8] = new Op(2, this::imp,  this::CLV);
        // $B9  LDA  abs,Y  4+
        ops[0xB9] = new Op(4, this::aby,  this::LDA);
        // $BA  TSX  impl   2
        ops[0xBA] = new Op(2, this::imp,  this::TSX);
        // $BC  LDY  abs,X  4+
        ops[0xBC] = new Op(4, this::abx,  this::LDY);
        // $BD  LDA  abs,X  4+
        ops[0xBD] = new Op(4, this::abx,  this::LDA);
        // $BE  LDX  abs,Y  4+
        ops[0xBE] = new Op(4, this::aby,  this::LDX);

        // $C0  CPY  imm    2
        ops[0xC0] = new Op(2, this::imm,  this::CPY);
        // $C1  CMP  (ind,X) 6
        ops[0xC1] = new Op(6, this::izx,  this::CMP);
        // $C4  CPY  zpg    3
        ops[0xC4] = new Op(3, this::zp,   this::CPY);
        // $C5  CMP  zpg    3
        ops[0xC5] = new Op(3, this::zp,   this::CMP);
        // $C6  DEC  zpg    5
        ops[0xC6] = new Op(5, this::zp,   this::DEC);
        // $C8  INY  impl   2
        ops[0xC8] = new Op(2, this::imp,  this::INY);
        // $C9  CMP  imm    2
        ops[0xC9] = new Op(2, this::imm,  this::CMP);
        // $CA  DEX  impl   2
        ops[0xCA] = new Op(2, this::imp,  this::DEX);
        // $CC  CPY  abs    4
        ops[0xCC] = new Op(4, this::abs_, this::CPY);
        // $CD  CMP  abs    4
        ops[0xCD] = new Op(4, this::abs_, this::CMP);
        // $CE  DEC  abs    6
        ops[0xCE] = new Op(6, this::abs_, this::DEC);

        // $D0  BNE  rel    2+
        ops[0xD0] = new Op(2, this::rel,  this::BNE);
        // $D1  CMP  (ind),Y 5+
        ops[0xD1] = new Op(5, this::izy,  this::CMP);
        // $D5  CMP  zpg,X  4
        ops[0xD5] = new Op(4, this::zpx,  this::CMP);
        // $D6  DEC  zpg,X  6
        ops[0xD6] = new Op(6, this::zpx,  this::DEC);
        // $D8  CLD  impl   2
        ops[0xD8] = new Op(2, this::imp,  this::CLD);
        // $D9  CMP  abs,Y  4+
        ops[0xD9] = new Op(4, this::aby,  this::CMP);
        // $DD  CMP  abs,X  4+
        ops[0xDD] = new Op(4, this::abx,  this::CMP);
        // $DE  DEC  abs,X  7
        ops[0xDE] = new Op(7, this::abx,  this::DEC);

        // $E0  CPX  imm    2
        ops[0xE0] = new Op(2, this::imm,  this::CPX);
        // $E1  SBC  (ind,X) 6
        ops[0xE1] = new Op(6, this::izx,  this::SBC);
        // $E4  CPX  zpg    3
        ops[0xE4] = new Op(3, this::zp,   this::CPX);
        // $E5  SBC  zpg    3
        ops[0xE5] = new Op(3, this::zp,   this::SBC);
        // $E6  INC  zpg    5
        ops[0xE6] = new Op(5, this::zp,   this::INC);
        // $E8  INX  impl   2
        ops[0xE8] = new Op(2, this::imp,  this::INX);
        // $E9  SBC  imm    2
        ops[0xE9] = new Op(2, this::imm,  this::SBC);
        // $EA  NOP  impl   2
        ops[0xEA] = new Op(2, this::imp,  this::NOP);
        // $EC  CPX  abs    4
        ops[0xEC] = new Op(4, this::abs_, this::CPX);
        // $ED  SBC  abs    4
        ops[0xED] = new Op(4, this::abs_, this::SBC);
        // $EE  INC  abs    6
        ops[0xEE] = new Op(6, this::abs_, this::INC);

        // $F0  BEQ  rel    2+
        ops[0xF0] = new Op(2, this::rel,  this::BEQ);
        // $F1  SBC  (ind),Y 5+
        ops[0xF1] = new Op(5, this::izy,  this::SBC);
        // $F5  SBC  zpg,X  4
        ops[0xF5] = new Op(4, this::zpx,  this::SBC);
        // $F6  INC  zpg,X  6
        ops[0xF6] = new Op(6, this::zpx,  this::INC);
        // $F8  SED  impl   2
        ops[0xF8] = new Op(2, this::imp,  this::SED);
        // $F9  SBC  abs,Y  4+
        ops[0xF9] = new Op(4, this::aby,  this::SBC);
        // $FD  SBC  abs,X  4+
        ops[0xFD] = new Op(4, this::abx,  this::SBC);
        // $FE  INC  abs,X  7
        ops[0xFE] = new Op(7, this::abx,  this::INC);
    }
}
