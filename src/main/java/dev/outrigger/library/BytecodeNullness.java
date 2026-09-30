package dev.outrigger.library;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Finds out from its bytecode what a method can return: {@code null} itself,
 * or the results of other method calls (whose nullness is decided elsewhere).
 *
 * <p>A data-flow analysis over the method's instructions. Null checks refine
 * the checked local variable on each branch, so a variable returned only after
 * {@code if (x == null) ...} doesn't count as nullable. Fields, array
 * elements and parameters count as unknown, not as nullable.
 *
 * <p>Returns reached only when a parameter is null
 * ({@code if (s == null) return null;}) don't count: the caller passed null.
 */
final class BytecodeNullness {

    /** A call whose result a method may return. */
    record Call(String owner, String name, String descriptor) {
    }

    /** What a method may return. */
    record Result(boolean returnsNull, Set<Call> returnedCalls) {
        static final Result NOTHING = new Result(false, Set.of());
    }

    private BytecodeNullness() {
    }

    static Result analyze(String owner, MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions.size() == 0) {
            return Result.NOTHING;
        }
        try {
            return new Run(owner, method).analyze();
        } catch (AnalyzerException | RuntimeException e) {
            return Result.NOTHING; // bytecode we don't understand: assume nothing
        }
    }

    /**
     * A value on the stack or in a local: may it be null, which calls may it
     * come from, which local was it loaded from, is it a parameter.
     *
     * <p>An extra local after the method's own holds whether the code is
     * reachable without a parameter having been null ({@code mayBeNull} true)
     * or only after one was ({@code false}).
     */
    private record NullValue(int size, boolean mayBeNull, Set<Call> calls, int local, boolean parameter)
            implements Value {
        NullValue(int size, boolean mayBeNull, Set<Call> calls, int local) {
            this(size, mayBeNull, calls, local, false);
        }

        static NullValue unknown(int size) {
            return new NullValue(size, false, Set.of(), -1);
        }

        @Override
        public int getSize() {
            return size;
        }
    }

    private static final class Run {

        private final String owner;
        private final MethodNode method;
        private final InsnList instructions;
        private final Frame<NullValue>[] frames;
        private final List<List<TryCatchBlockNode>> handlers = new ArrayList<>();
        private final Deque<Integer> queue = new ArrayDeque<>();
        private final boolean[] queued;
        private final NullnessInterpreter interpreter = new NullnessInterpreter();

        @SuppressWarnings("unchecked")
        Run(String owner, MethodNode method) {
            this.owner = owner;
            this.method = method;
            this.instructions = method.instructions;
            this.frames = (Frame<NullValue>[]) new Frame<?>[instructions.size()];
            this.queued = new boolean[instructions.size()];
            for (int i = 0; i < instructions.size(); i++) {
                handlers.add(new ArrayList<>());
            }
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                for (int i = instructions.indexOf(block.start); i < instructions.indexOf(block.end); i++) {
                    handlers.get(i).add(block);
                }
            }
        }

        Result analyze() throws AnalyzerException {
            mergeInto(0, initialFrame());
            boolean returnsNull = false;
            Set<Call> calls = new HashSet<>();
            int budget = instructions.size() * 50;

            while (!queue.isEmpty()) {
                if (--budget < 0) {
                    return Result.NOTHING; // doesn't settle: assume nothing
                }
                int index = queue.poll();
                queued[index] = false;
                Frame<NullValue> frame = frames[index];
                AbstractInsnNode insn = instructions.get(index);
                int opcode = insn.getOpcode();

                // an exception can leave from any instruction in a try block
                for (TryCatchBlockNode block : handlers.get(index)) {
                    Frame<NullValue> handler = new Frame<>(frame);
                    handler.clearStack();
                    handler.push(NullValue.unknown(1));
                    mergeInto(instructions.indexOf(block.handler), handler);
                }

                if (opcode == Opcodes.ARETURN) {
                    if (frame.getLocal(guard()).mayBeNull()) { // not only after a parameter was null
                        NullValue returned = frame.getStack(frame.getStackSize() - 1);
                        returnsNull |= returned.mayBeNull();
                        calls.addAll(returned.calls());
                    }
                    continue;
                }
                if (opcode == Opcodes.JSR || opcode == Opcodes.RET) {
                    return Result.NOTHING; // old-style subroutines
                }
                if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) {
                    branchOnNull(index, frame, (JumpInsnNode) insn);
                    continue;
                }

                Frame<NullValue> next = new Frame<>(frame);
                if (opcode >= 0) { // labels, line numbers and stack map frames are no instructions
                    next.execute(insn, interpreter);
                }
                if (insn instanceof JumpInsnNode jump) {
                    mergeInto(instructions.indexOf(jump.label), next);
                    if (opcode != Opcodes.GOTO) {
                        mergeInto(index + 1, next);
                    }
                } else if (insn instanceof TableSwitchInsnNode table) {
                    mergeInto(instructions.indexOf(table.dflt), next);
                    for (LabelNode label : table.labels) {
                        mergeInto(instructions.indexOf(label), next);
                    }
                } else if (insn instanceof LookupSwitchInsnNode lookup) {
                    mergeInto(instructions.indexOf(lookup.dflt), next);
                    for (LabelNode label : lookup.labels) {
                        mergeInto(instructions.indexOf(label), next);
                    }
                } else if (!isExit(opcode) && index + 1 < instructions.size()) {
                    mergeInto(index + 1, next);
                }
            }
            return new Result(returnsNull, calls);
        }

        /** {@code if (x == null)}: on each branch, the tested local variable is known to be null or not. */
        private void branchOnNull(int index, Frame<NullValue> frame, JumpInsnNode insn) throws AnalyzerException {
            NullValue tested = frame.getStack(frame.getStackSize() - 1);
            Frame<NullValue> fallThrough = new Frame<>(frame);
            fallThrough.execute(insn, interpreter);
            Frame<NullValue> jump = new Frame<>(fallThrough);
            boolean jumpsIfNull = insn.getOpcode() == Opcodes.IFNULL;
            if (tested.local() >= 0) {
                NullValue isNull = new NullValue(1, true, Set.of(), -1);
                NullValue notNull = NullValue.unknown(1);
                jump.setLocal(tested.local(), jumpsIfNull ? isNull : notNull);
                fallThrough.setLocal(tested.local(), jumpsIfNull ? notNull : isNull);
            }
            if (tested.parameter()) {
                (jumpsIfNull ? jump : fallThrough).setLocal(guard(), new NullValue(1, false, Set.of(), -1));
            }
            mergeInto(index + 1, fallThrough);
            mergeInto(instructions.indexOf(insn.label), jump);
        }

        private Frame<NullValue> initialFrame() {
            Frame<NullValue> frame = new Frame<>(method.maxLocals + 1, method.maxStack);
            int local = 0;
            if ((method.access & Opcodes.ACC_STATIC) == 0) {
                frame.setLocal(local++, NullValue.unknown(1)); // this
            }
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                frame.setLocal(local++, new NullValue(argument.getSize(), false, Set.of(), -1, true));
                if (argument.getSize() == 2) {
                    frame.setLocal(local++, NullValue.unknown(1));
                }
            }
            while (local < method.maxLocals) {
                frame.setLocal(local++, NullValue.unknown(1));
            }
            frame.setLocal(guard(), new NullValue(1, true, Set.of(), -1)); // reachable normally
            return frame;
        }

        /** The extra local that tells whether code is reached only after a parameter was null. */
        private int guard() {
            return method.maxLocals;
        }

        private void mergeInto(int index, Frame<NullValue> frame) throws AnalyzerException {
            if (frames[index] == null) {
                frames[index] = new Frame<>(frame);
            } else if (!frames[index].merge(frame, interpreter)) {
                return;
            }
            if (!queued[index]) {
                queued[index] = true;
                queue.add(index);
            }
        }

        private static boolean isExit(int opcode) {
            return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW;
        }

        @Override
        public String toString() {
            return owner + "." + method.name + method.desc;
        }
    }

    /** Tracks nullness; the stack effects of every other instruction come from ASM's basic interpreter. */
    private static final class NullnessInterpreter extends Interpreter<NullValue> {

        private final BasicInterpreter basic = new BasicInterpreter();

        NullnessInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public NullValue newValue(Type type) {
            if (type == Type.VOID_TYPE) {
                return null;
            }
            return NullValue.unknown(type == null ? 1 : type.getSize());
        }

        @Override
        public NullValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            if (insn.getOpcode() == Opcodes.ACONST_NULL) {
                return new NullValue(1, true, Set.of(), -1);
            }
            return sized(basic.newOperation(insn));
        }

        @Override
        public NullValue copyOperation(AbstractInsnNode insn, NullValue value) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.ALOAD) {
                return new NullValue(value.size(), value.mayBeNull(), value.calls(), ((VarInsnNode) insn).var,
                        value.parameter());
            }
            if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                return new NullValue(value.size(), value.mayBeNull(), value.calls(), -1, value.parameter());
            }
            return value;
        }

        @Override
        public NullValue unaryOperation(AbstractInsnNode insn, NullValue value) throws AnalyzerException {
            if (insn.getOpcode() == Opcodes.CHECKCAST) {
                return value;
            }
            return sized(basic.unaryOperation(insn, basic(value)));
        }

        @Override
        public NullValue binaryOperation(AbstractInsnNode insn, NullValue value1, NullValue value2)
                throws AnalyzerException {
            return sized(basic.binaryOperation(insn, basic(value1), basic(value2)));
        }

        @Override
        public NullValue ternaryOperation(AbstractInsnNode insn, NullValue value1, NullValue value2,
                NullValue value3) {
            return null;
        }

        @Override
        public NullValue naryOperation(AbstractInsnNode insn, List<? extends NullValue> values) {
            Type returned;
            if (insn instanceof MethodInsnNode call) {
                returned = Type.getReturnType(call.desc);
                if (returned.getSort() == Type.OBJECT || returned.getSort() == Type.ARRAY) {
                    return new NullValue(1, false, Set.of(new Call(call.owner, call.name, call.desc)), -1);
                }
            } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                returned = Type.getReturnType(dynamic.desc); // lambdas, string concatenation: not null
            } else {
                return NullValue.unknown(1); // multianewarray
            }
            return returned == Type.VOID_TYPE ? null : NullValue.unknown(returned.getSize());
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, NullValue value, NullValue expected) {
            // returns are collected by the analysis itself
        }

        @Override
        public NullValue merge(NullValue value1, NullValue value2) {
            if (value1.equals(value2)) {
                return value1;
            }
            Set<Call> calls = value1.calls();
            if (!value1.calls().containsAll(value2.calls())) {
                calls = new HashSet<>(value1.calls());
                calls.addAll(value2.calls());
                calls = Set.copyOf(calls);
            }
            return new NullValue(value1.size(), value1.mayBeNull() || value2.mayBeNull(), calls,
                    value1.local() == value2.local() ? value1.local() : -1, value1.parameter() && value2.parameter());
        }

        private static NullValue sized(BasicValue value) {
            return value == null ? null : NullValue.unknown(value.getSize());
        }

        private static BasicValue basic(NullValue value) {
            if (value == null) {
                return null;
            }
            return value.size() == 2 ? BasicValue.LONG_VALUE : BasicValue.REFERENCE_VALUE;
        }
    }
}
