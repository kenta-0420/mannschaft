package com.mannschaft.app.common.architecture;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * 番人3（{@link PlaintextSecretTokenGuardRules#selfIssuedTokens}）の、メソッド内の乱数の値の流れ解析。
 *
 * <p>スタックと局所変数の意味（全命令・カテゴリ2の値・DUP 系の並べ替え）、制御フロー（通常の辺・後方辺・例外辺）、
 * 不動点反復は ASM 標準の {@link Analyzer} に任せ、ここでは値の抽象（{@link TaintValue}: 乱数由来の印と、
 * 配列を作った場所の集合）と、その遷移（{@link TaintInterpreter}）だけを定める。合流は和集合（may 解析）である。
 * <b>停止性</b>: 値の格子は「印（真偽）× 配列を作った場所（メソッド内の命令と引数の有限集合）の部分集合」で有限であり、
 * 合流は和集合なので単調に増え、{@link Analyzer} の不動点反復は有限回で止まる。</p>
 *
 * <p>配列の別名は「同じ場所で作られた配列」として扱う。{@code SecureRandom#nextBytes(a)} や乱数由来の値の配列要素への代入は、
 * その時点のフレーム（局所変数とスタック）で {@code a} と作った場所が重なる値すべてに印を付ける（{@link TaintFrame}）。
 * 多次元配列の内側の配列（{@code AALOAD} の結果）は外側の配列を作った場所を受け継ぐ（内側への書込みで外側に印が付き、
 * 読出しで印が伝わる保守的な近似）。
 * 作った場所が分からない配列（フィールドや戻り値の配列）へ {@code nextBytes} したときは {@link #ANY_CALL} を入れ、
 * 同居だけで違反とする側に倒す。</p>
 *
 * <p>既知の限界（検出しない）: メソッドをまたぐ流れ・フィールド経由・ラムダの本体・リフレクション
 * （{@link PlaintextSecretTokenGuardRules} のクラス Javadoc 参照）。</p>
 */
final class PlaintextSecretRandomFlowAnalyzer {

    /** 判定が追えない形のときに入れる、全呼び出しに届いたとみなす印。 */
    static final String ANY_CALL = "*";

    private static final String UUID_OWNER = Type.getInternalName(UUID.class);
    private static final String SECURE_RANDOM_OWNER = Type.getInternalName(SecureRandom.class);
    private static final String CONSTRUCTOR = "<init>";

    private PlaintextSecretRandomFlowAnalyzer() {
    }

    /**
     * クラスファイルから、メソッドごと（キーは {@code 名前 + 記述子}）に、乱数由来の値を引数に受けた呼び出し
     * （{@code 所有クラスの完全修飾名 + "." + メソッド名}）の集合を取り出す。
     */
    static Map<String, Set<String>> analyze(InputStream classFile) throws IOException {
        ClassNode type = new ClassNode();
        new ClassReader(classFile).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Map<String, Set<String>> result = new HashMap<>();
        for (MethodNode method : type.methods) {
            Set<String> reached = new HashSet<>();
            result.put(method.name + method.desc, reached);
            if (method.instructions.size() == 0) {
                continue;
            }
            try {
                new TaintAnalyzer(new TaintInterpreter(reached)).analyze(type.name, method);
            } catch (AnalyzerException e) {
                throw new IllegalStateException("値の流れを解析できません: " + type.name + "#" + method.name, e);
            }
        }
        return result;
    }

    /**
     * 抽象値。{@code size} は 1 か 2（カテゴリ）、{@code sites} はこの値でありうる配列を作った場所。
     * 作った場所は命令の番号（0 以上）か、引数の局所変数番号から作る負の番号。
     */
    record TaintValue(int size, boolean tainted, Set<Integer> sites) implements Value {
        static final TaintValue UNINITIALIZED = new TaintValue(1, false, Set.of());

        static TaintValue of(int size, boolean tainted) {
            return new TaintValue(size, tainted, Set.of());
        }

        @Override
        public int getSize() {
            return size;
        }

        TaintValue withTaint() {
            return tainted ? this : new TaintValue(size, true, sites);
        }

        boolean sharesSite(Set<Integer> others) {
            for (int site : others) {
                if (sites.contains(site)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 値の遷移。結果の印は、入力のどれかが乱数由来なら乱数由来（演算・変換・呼び出しの戻り値を貫通する）。 */
    static final class TaintInterpreter extends Interpreter<TaintValue> {
        private final Set<String> reached;

        TaintInterpreter(Set<String> reached) {
            super(Opcodes.ASM9);
            this.reached = reached;
        }

        void reach(String call) {
            reached.add(call);
        }

        @Override
        public TaintValue newValue(Type type) {
            if (type == null) {
                return TaintValue.UNINITIALIZED;
            }
            if (type.getSort() == Type.VOID) {
                return null;
            }
            return TaintValue.of(type.getSize(), false);
        }

        @Override
        public TaintValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
            TaintValue value = newValue(type);
            if (type.getSort() == Type.ARRAY) {
                return new TaintValue(value.size(), false, Set.of(-1 - local));
            }
            return value;
        }

        @Override
        public TaintValue newOperation(AbstractInsnNode insn) {
            return switch (insn.getOpcode()) {
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> TaintValue.of(2, false);
                case Opcodes.LDC -> {
                    Object constant = ((LdcInsnNode) insn).cst;
                    yield TaintValue.of(constant instanceof Long || constant instanceof Double ? 2 : 1, false);
                }
                case Opcodes.GETSTATIC -> TaintValue.of(Type.getType(((FieldInsnNode) insn).desc).getSize(), false);
                // NEW は <init> の後に印を付け替えるため、命令ごとに別の値（同一性で追う）にする
                case Opcodes.NEW -> new TaintValue(1, false, Set.of());
                default -> TaintValue.of(1, false);
            };
        }

        @Override
        public TaintValue copyOperation(AbstractInsnNode insn, TaintValue value) {
            return value;
        }

        @Override
        public TaintValue unaryOperation(AbstractInsnNode insn, TaintValue value) {
            int opcode = insn.getOpcode();
            return switch (opcode) {
                case Opcodes.INEG, Opcodes.FNEG, Opcodes.I2F, Opcodes.L2I, Opcodes.L2F, Opcodes.F2I, Opcodes.D2I,
                     Opcodes.D2F, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.ARRAYLENGTH ->
                        TaintValue.of(1, value.tainted());
                case Opcodes.LNEG, Opcodes.DNEG, Opcodes.I2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2L, Opcodes.F2D,
                     Opcodes.D2L -> TaintValue.of(2, value.tainted());
                case Opcodes.IINC -> TaintValue.of(1, value.tainted());
                // 配列を作った場所は命令の番号で識別する
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> new TaintValue(1, false, Set.of(insnIndex(insn)));
                case Opcodes.CHECKCAST -> value;
                case Opcodes.INSTANCEOF -> TaintValue.of(1, false);
                case Opcodes.GETFIELD -> TaintValue.of(Type.getType(((FieldInsnNode) insn).desc).getSize(), false);
                // 分岐・switch・return・throw・PUTSTATIC・MONITOR* は値を返さない
                default -> null;
            };
        }

        @Override
        public TaintValue binaryOperation(AbstractInsnNode insn, TaintValue value1, TaintValue value2) {
            int opcode = insn.getOpcode();
            boolean tainted = value1.tainted() || value2.tainted();
            return switch (opcode) {
                // 参照の要素（多次元配列の内側の配列を含む）は外側の配列を作った場所を受け継ぐ。内側への書込みで外側に印が付き、
                // 読出しで印が伝わる保守的な近似（一次元と同じ扱いになる）
                case Opcodes.AALOAD -> new TaintValue(1, value1.tainted(), value1.sites());
                case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD ->
                        TaintValue.of(1, value1.tainted());
                case Opcodes.LALOAD, Opcodes.DALOAD -> TaintValue.of(2, value1.tainted());
                case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LSHL, Opcodes.LSHR,
                     Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL,
                     Opcodes.DDIV, Opcodes.DREM -> TaintValue.of(2, tainted);
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                     Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.PUTFIELD -> null;
                default -> TaintValue.of(1, tainted);
            };
        }

        @Override
        public TaintValue ternaryOperation(AbstractInsnNode insn, TaintValue value1, TaintValue value2,
                                           TaintValue value3) {
            return null;
        }

        @Override
        public TaintValue naryOperation(AbstractInsnNode insn, List<? extends TaintValue> values) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MULTIANEWARRAY) {
                return new TaintValue(1, false, Set.of(insnIndex(insn)));
            }
            if (opcode == Opcodes.INVOKEDYNAMIC) {
                boolean tainted = values.stream().anyMatch(TaintValue::tainted);
                return newValueOrNull(Type.getReturnType(((InvokeDynamicInsnNode) insn).desc), tainted);
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            int argumentStart = opcode == Opcodes.INVOKESTATIC ? 0 : 1;
            boolean argumentTainted = false;
            for (int i = argumentStart; i < values.size(); i++) {
                argumentTainted |= values.get(i).tainted();
            }
            if (argumentTainted) {
                reach(call.owner.replace('/', '.') + "." + call.name);
            }
            Type returnType = Type.getReturnType(call.desc);
            if (call.owner.equals(UUID_OWNER) && call.name.equals("randomUUID")) {
                return newValueOrNull(returnType, true);
            }
            boolean receiverTainted = argumentStart == 1 && values.get(0).tainted();
            return newValueOrNull(returnType, argumentTainted || receiverTainted);
        }

        private static TaintValue newValueOrNull(Type type, boolean tainted) {
            return type.getSort() == Type.VOID ? null : TaintValue.of(type.getSize(), tainted);
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, TaintValue value, TaintValue expected) {
            // 戻り値は追わない（メソッドをまたぐ流れは既知の限界）
        }

        @Override
        public TaintValue merge(TaintValue value1, TaintValue value2) {
            int size = value1.size() == value2.size() ? value1.size() : 1;
            boolean tainted = value1.tainted() || value2.tainted();
            if (size == value1.size() && tainted == value1.tainted() && value1.sites().containsAll(value2.sites())) {
                return value1;
            }
            Set<Integer> sites = new HashSet<>(value1.sites());
            sites.addAll(value2.sites());
            return new TaintValue(size, tainted, Set.copyOf(sites));
        }

        private static int insnIndex(AbstractInsnNode insn) {
            int index = 0;
            for (AbstractInsnNode current = insn.getPrevious(); current != null; current = current.getPrevious()) {
                index++;
            }
            return index;
        }
    }

    /** 配列への書込み（{@code nextBytes}・要素への代入）と、乱数由来の引数を受けた {@code <init>} を反映するフレーム。 */
    static final class TaintFrame extends Frame<TaintValue> {
        TaintFrame(int numLocals, int maxStack) {
            super(numLocals, maxStack);
        }

        TaintFrame(Frame<? extends TaintValue> frame) {
            super(frame);
        }

        @Override
        public void execute(AbstractInsnNode insn, Interpreter<TaintValue> interpreter) throws AnalyzerException {
            int opcode = insn.getOpcode();
            int top = getStackSize() - 1;
            if (insn instanceof MethodInsnNode call) {
                int argumentCount = Type.getArgumentCount(call.desc);
                if (call.owner.equals(SECURE_RANDOM_OWNER) && call.name.equals("nextBytes") && argumentCount == 1) {
                    TaintValue array = getStack(top);
                    super.execute(insn, interpreter);
                    if (array.sites().isEmpty()) {
                        ((TaintInterpreter) interpreter).reach(ANY_CALL);
                    } else {
                        taintSites(array.sites());
                    }
                    return;
                }
                if (opcode == Opcodes.INVOKESPECIAL && call.name.equals(CONSTRUCTOR)) {
                    TaintValue receiver = getStack(top - argumentCount);
                    boolean argumentTainted = false;
                    for (int i = 0; i < argumentCount; i++) {
                        argumentTainted |= getStack(top - i).tainted();
                    }
                    super.execute(insn, interpreter);
                    if (argumentTainted) {
                        // new X(乱数) で作ったオブジェクトは乱数由来（NEW と DUP で積んだ同じ値を付け替える）
                        replaceIdentical(receiver, receiver.withTaint());
                    }
                    return;
                }
            }
            if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) {
                TaintValue value = getStack(top);
                TaintValue array = getStack(top - 2);
                super.execute(insn, interpreter);
                if (value.tainted()) {
                    taintSites(array.sites());
                }
                return;
            }
            super.execute(insn, interpreter);
        }

        /** 作った場所が重なる配列の値すべて（局所変数とスタック）に印を付ける。 */
        private void taintSites(Set<Integer> sites) {
            if (sites.isEmpty()) {
                return;
            }
            for (int i = 0; i < getLocals(); i++) {
                TaintValue local = getLocal(i);
                if (local != null && local.sharesSite(sites)) {
                    setLocal(i, local.withTaint());
                }
            }
            for (int i = 0; i < getStackSize(); i++) {
                TaintValue operand = getStack(i);
                if (operand.sharesSite(sites)) {
                    setStack(i, operand.withTaint());
                }
            }
        }

        private void replaceIdentical(TaintValue target, TaintValue replacement) {
            for (int i = 0; i < getLocals(); i++) {
                if (getLocal(i) == target) {
                    setLocal(i, replacement);
                }
            }
            for (int i = 0; i < getStackSize(); i++) {
                if (getStack(i) == target) {
                    setStack(i, replacement);
                }
            }
        }
    }

    /** {@link TaintFrame} を使う {@link Analyzer}。 */
    static final class TaintAnalyzer extends Analyzer<TaintValue> {
        TaintAnalyzer(TaintInterpreter interpreter) {
            super(interpreter);
        }

        @Override
        protected Frame<TaintValue> newFrame(int numLocals, int numStack) {
            return new TaintFrame(numLocals, numStack);
        }

        @Override
        protected Frame<TaintValue> newFrame(Frame<? extends TaintValue> frame) {
            return new TaintFrame(frame);
        }
    }
}
