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
 * 配列を作った場所の集合）と、その遷移（{@link TaintInterpreter}）だけを定める。値の合流は和集合（may 解析）である。</p>
 *
 * <p><b>配列の別名</b>は、メソッド内の配列を作った場所（命令・引数）の上の union-find による同値類（{@link AliasClasses}）で
 * まとめて扱い、乱数の印は値ではなく類に付ける。</p>
 * <ul>
 *   <li>{@code AASTORE} で参照を配列へ格納したら、格納した値の場所と格納先の場所を同じ類へ併合する</li>
 *   <li>{@code AALOAD} の結果（内側の配列や要素）は、格納先と同じ類に属する（格納先の場所をそのまま持つ）</li>
 *   <li>{@code SecureRandom#nextBytes(a)} や乱数由来の値の要素への代入は、{@code a} の類に印を付ける</li>
 *   <li>値を読むときは、値自身の印か、値の場所のどれかの類の印があれば乱数由来とみなす</li>
 * </ul>
 * <p>これで多次元・格納の前後・コピーした別名・分岐で合流した別名を、次数や形ごとの継ぎ当てなしに同じ規則で追う。
 * 類の印は流れに依らない（同じ類の配列は、書込みの前後を問わず乱数由来とみなす保守的な近似）。
 * 作った場所が分からない配列（フィールドや戻り値の配列）へ {@code nextBytes} したときは {@link #ANY_CALL} を入れ、
 * 同居だけで違反とする側に倒す。</p>
 *
 * <p><b>停止性と単調性</b>: 値の格子は「印（真偽）× 作った場所（メソッド内の命令と引数の有限集合）の部分集合」で有限であり、
 * 合流は和集合なので単調に増える。類は併合しかせず（分割しない）、類の印は付けるだけで外さないので、類の状態も単調に増え
 * 有限回で止まる。解析中に類の状態が変わると、それより前に評価した値が古いままでありうるため、{@link Analyzer} の実行を
 * 類の状態が変わらなくなるまで繰り返す（外側の不動点）。各回の到達集合は最終回の部分集合なので、全回の和をとってよい。</p>
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
            AliasClasses aliases = new AliasClasses();
            try {
                long before;
                do {
                    before = aliases.version();
                    new TaintAnalyzer(new TaintInterpreter(reached, aliases)).analyze(type.name, method);
                } while (aliases.version() != before);
            } catch (AnalyzerException e) {
                throw new IllegalStateException("値の流れを解析できません: " + type.name + "#" + method.name, e);
            }
        }
        return result;
    }

    /**
     * 配列を作った場所の上の別名の同値類（union-find）と、類ごとの乱数の印。併合と印付けしかしないので単調に増える。
     * {@link #version()} は状態が変わるたびに増える。
     */
    static final class AliasClasses {
        private final Map<Integer, Integer> parent = new HashMap<>();
        private final Set<Integer> taintedRoots = new HashSet<>();
        private long version;

        long version() {
            return version;
        }

        private int find(int site) {
            int root = site;
            Integer up;
            while ((up = parent.get(root)) != null && up != root) {
                root = up;
            }
            int current = site;
            while ((up = parent.get(current)) != null && up != root) {
                parent.put(current, root);
                current = up;
            }
            return root;
        }

        /** 場所の集合をすべて同じ類へ併合する。 */
        void union(Set<Integer> sites) {
            Integer first = null;
            for (int site : sites) {
                int root = find(site);
                if (first == null) {
                    first = root;
                } else if (root != first) {
                    parent.put(root, first);
                    if (taintedRoots.remove(root)) {
                        taintedRoots.add(first);
                    }
                    version++;
                }
            }
        }

        /** 場所の集合が属する類すべてに乱数の印を付ける。 */
        void taint(Set<Integer> sites) {
            for (int site : sites) {
                if (taintedRoots.add(find(site))) {
                    version++;
                }
            }
        }

        boolean anyTainted(Set<Integer> sites) {
            for (int site : sites) {
                if (taintedRoots.contains(find(site))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 抽象値。{@code size} は 1 か 2（カテゴリ）、{@code tainted} は値自身の乱数の印、{@code sites} はこの値でありうる
     * 配列を作った場所（命令の番号は 0 以上、引数は局所変数番号から作る負の番号）。配列の印は {@link AliasClasses} の類が持つ。
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
    }

    /** 値の遷移。結果の印は、入力のどれかが乱数由来なら乱数由来（演算・変換・呼び出しの戻り値を貫通する）。 */
    static final class TaintInterpreter extends Interpreter<TaintValue> {
        private final Set<String> reached;
        private final AliasClasses aliases;

        TaintInterpreter(Set<String> reached, AliasClasses aliases) {
            super(Opcodes.ASM9);
            this.reached = reached;
            this.aliases = aliases;
        }

        void reach(String call) {
            reached.add(call);
        }

        /** 値が乱数由来か（値自身の印か、値の場所の類の印）。 */
        boolean isTainted(TaintValue value) {
            return value.tainted() || aliases.anyTainted(value.sites());
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
                     Opcodes.D2F, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.ARRAYLENGTH, Opcodes.IINC ->
                        TaintValue.of(1, isTainted(value));
                case Opcodes.LNEG, Opcodes.DNEG, Opcodes.I2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2L, Opcodes.F2D,
                     Opcodes.D2L -> TaintValue.of(2, isTainted(value));
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
            boolean tainted = isTainted(value1) || isTainted(value2);
            return switch (opcode) {
                // 参照の要素（内側の配列を含む）は格納先と同じ類に属する
                case Opcodes.AALOAD -> new TaintValue(1, false, value1.sites());
                case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD ->
                        TaintValue.of(1, isTainted(value1));
                case Opcodes.LALOAD, Opcodes.DALOAD -> TaintValue.of(2, isTainted(value1));
                case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LSHL, Opcodes.LSHR,
                     Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL,
                     Opcodes.DDIV, Opcodes.DREM -> TaintValue.of(2, tainted);
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                     Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.PUTFIELD -> null;
                default -> TaintValue.of(1, tainted);
            };
        }

        @Override
        public TaintValue ternaryOperation(AbstractInsnNode insn, TaintValue array, TaintValue index,
                                           TaintValue value) {
            // 配列要素への代入（xASTORE）
            if (insn.getOpcode() == Opcodes.AASTORE && !value.sites().isEmpty()) {
                Set<Integer> merged = new HashSet<>(array.sites());
                merged.addAll(value.sites());
                aliases.union(merged);
            }
            if (isTainted(value)) {
                aliases.taint(array.sites());
            }
            return null;
        }

        @Override
        public TaintValue naryOperation(AbstractInsnNode insn, List<? extends TaintValue> values) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MULTIANEWARRAY) {
                return new TaintValue(1, false, Set.of(insnIndex(insn)));
            }
            if (opcode == Opcodes.INVOKEDYNAMIC) {
                boolean tainted = values.stream().anyMatch(this::isTainted);
                return newValueOrNull(Type.getReturnType(((InvokeDynamicInsnNode) insn).desc), tainted);
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            int argumentStart = opcode == Opcodes.INVOKESTATIC ? 0 : 1;
            boolean argumentTainted = false;
            for (int i = argumentStart; i < values.size(); i++) {
                argumentTainted |= isTainted(values.get(i));
            }
            if (argumentTainted) {
                reach(call.owner.replace('/', '.') + "." + call.name);
            }
            Type returnType = Type.getReturnType(call.desc);
            if (call.owner.equals(UUID_OWNER) && call.name.equals("randomUUID")) {
                return newValueOrNull(returnType, true);
            }
            if (call.owner.equals(SECURE_RANDOM_OWNER) && call.name.equals("nextBytes") && values.size() == 2) {
                Set<Integer> sites = values.get(1).sites();
                if (sites.isEmpty()) {
                    reach(ANY_CALL);
                } else {
                    aliases.taint(sites);
                }
                return null;
            }
            boolean receiverTainted = argumentStart == 1 && isTainted(values.get(0));
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

    /** 乱数由来の引数を受けた {@code <init>} で、作ったオブジェクト（NEW と DUP で積んだ同じ値）に印を付け替えるフレーム。 */
    static final class TaintFrame extends Frame<TaintValue> {
        TaintFrame(int numLocals, int maxStack) {
            super(numLocals, maxStack);
        }

        TaintFrame(Frame<? extends TaintValue> frame) {
            super(frame);
        }

        @Override
        public void execute(AbstractInsnNode insn, Interpreter<TaintValue> interpreter) throws AnalyzerException {
            if (insn instanceof MethodInsnNode call && insn.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals(CONSTRUCTOR)) {
                int top = getStackSize() - 1;
                int argumentCount = Type.getArgumentCount(call.desc);
                TaintValue receiver = getStack(top - argumentCount);
                boolean argumentTainted = false;
                for (int i = 0; i < argumentCount; i++) {
                    argumentTainted |= ((TaintInterpreter) interpreter).isTainted(getStack(top - i));
                }
                super.execute(insn, interpreter);
                if (argumentTainted) {
                    replaceIdentical(receiver, receiver.withTaint());
                }
                return;
            }
            super.execute(insn, interpreter);
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
