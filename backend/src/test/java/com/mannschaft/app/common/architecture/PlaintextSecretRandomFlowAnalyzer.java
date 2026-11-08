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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
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
 *   <li>{@code AALOAD} の結果（内側の配列や要素）は、格納先と同じ類に属する（格納先の場所をそのまま持つ）。
 *       配列の値自体に印があれば（{@code split} の戻り値など）、結果も印付きとする</li>
 *   <li>{@code SecureRandom#nextBytes(a)} や乱数由来の値の要素への代入は、{@code a} の類に印を付ける</li>
 *   <li>値を読むときは、値自身の印か、値の場所のどれかの類の印があれば乱数由来とみなす</li>
 * </ul>
 * <p>これで多次元・格納の前後・コピーした別名・分岐で合流した別名を、次数や形ごとの継ぎ当てなしに同じ規則で追う。
 * 類の印は流れに依らない（同じ類の配列は、書込みの前後を問わず乱数由来とみなす保守的な近似）。
 * 参照値を生む命令（NEW・NEWARRAY・ANEWARRAY・MULTIANEWARRAY・INVOKE* の戻り値・メソッド引数・AALOAD・
 * GETFIELD・GETSTATIC・参照の LDC）は、配列型に限らずすべて命令位置（引数は局所変数番号）をキーとする場所を持ち、
 * 場所の空な参照値は生まれない。CHECKCAST は新しい場所を作らず元の値の場所を引き継ぐ（同じ参照を2回キャストしても
 * 同一性を保つ）。これらの場所も同じ union-find の類に乗る。格納による併合は配列でありうる値（配列型・Object 等）に限る。フィールドの読み出しは1回ごとに別の場所なので、
 * フィールドをまたぐ流れは既知の限界のまま、読み出したローカル配列に書いて同じメソッド内で読む流れは検出する。
 * それでも場所が空の値（{@code null} 定数など）へ {@code nextBytes} したときは {@link #ANY_CALL} を入れ、
 * 同居だけで違反とする側に倒す。</p>
 *
 * <p><b>既知の偽陽性（保守的近似。2026-11-05 殿裁定で許容）</b>: 格納は本来「格納先が格納値を含む」包含関係だが、
 * これを同値関係として併合するため、共通の格納先を通じて別々の配列が同じ類になる。たとえば乱数を入れた配列ときれいな配列を
 * 同じ外側の配列へ格納すると、きれいな配列から token 系へ詰めた値も乱数由来とみなして検出する。番人は安全側に倒す方針で、
 * 本番の違反は0件のため精密化しない（{@code PlaintextSecretTokenGuardConditionTest} に検出を固定する fixture を置く）。
 * 誤検出したときの回避策: 配列を共通の格納先に入れずに分ける。分けられない事情があれば {@code @PlaintextTokenDebt}
 * （台帳登録済みの未完了 CMP）を付けて債務として扱う。</p>
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
                    new TaintAnalyzer(new TaintInterpreter(reached, aliases, method.instructions)).analyze(type.name, method);
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
     * 抽象値。{@code size} は 1 か 2（カテゴリ）、{@code tainted} は値自身の乱数の印、{@code sites} はこの参照値でありうる
     * 参照を生んだ場所（命令の番号は 0 以上、引数は局所変数番号から作る負の番号）。配列の印は {@link AliasClasses} の類が持つ。
     * {@code mayBeArray} は配列でありうるか（配列型か、{@code Object}・{@code Cloneable}・{@code Serializable} 型）。
     * 格納による類の併合は配列でありうる値に限る（文字列など配列でない参照を格納しても、格納先の配列と同じ類にしない）。
     */
    record TaintValue(int size, boolean tainted, Set<Integer> sites, boolean mayBeArray) implements Value {
        static final TaintValue UNINITIALIZED = new TaintValue(1, false, Set.of(), false);

        static TaintValue of(int size, boolean tainted) {
            return new TaintValue(size, tainted, Set.of(), false);
        }

        /** 型が配列でありうるか（配列型、または配列の上位型である Object・Cloneable・Serializable）。 */
        static boolean mayBeArray(Type type) {
            if (type.getSort() == Type.ARRAY) {
                return true;
            }
            if (type.getSort() != Type.OBJECT) {
                return false;
            }
            String name = type.getInternalName();
            return name.equals("java/lang/Object") || name.equals("java/lang/Cloneable")
                    || name.equals("java/io/Serializable");
        }

        static boolean isReference(Type type) {
            return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
        }

        @Override
        public int getSize() {
            return size;
        }

        TaintValue withTaint() {
            return tainted ? this : new TaintValue(size, true, sites, mayBeArray);
        }
    }

    /** 値の遷移。結果の印は、入力のどれかが乱数由来なら乱数由来（演算・変換・呼び出しの戻り値を貫通する）。 */
    static final class TaintInterpreter extends Interpreter<TaintValue> {
        private final Set<String> reached;
        private final AliasClasses aliases;
        private final InsnList instructions;

        TaintInterpreter(Set<String> reached, AliasClasses aliases, InsnList instructions) {
            super(Opcodes.ASM9);
            this.reached = reached;
            this.aliases = aliases;
            this.instructions = instructions;
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
            if (TaintValue.isReference(type)) {
                return new TaintValue(1, false, Set.of(-1 - local), TaintValue.mayBeArray(type));
            }
            return newValue(type);
        }

        @Override
        public TaintValue newOperation(AbstractInsnNode insn) {
            return switch (insn.getOpcode()) {
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> TaintValue.of(2, false);
                case Opcodes.LDC -> {
                    Object constant = ((LdcInsnNode) insn).cst;
                    if (constant instanceof Long || constant instanceof Double) {
                        yield TaintValue.of(2, false);
                    }
                    boolean primitive = constant instanceof Integer || constant instanceof Float;
                    yield primitive ? TaintValue.of(1, false) : new TaintValue(1, false, Set.of(insnIndex(insn)), false);
                }
                case Opcodes.GETSTATIC -> fieldValue(insn);
                // NEW は参照を生む場所を持つ。<init> の後に印を付け替えるため、命令ごとに別の値（同一性で追う）にする
                case Opcodes.NEW -> new TaintValue(1, false, Set.of(insnIndex(insn)), false);
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
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> new TaintValue(1, false, Set.of(insnIndex(insn)), true);
                // CHECKCAST は新しい場所を作らず、元の値の場所をそのまま引き継ぐ（同じ参照の2回のキャストでも同一性を保つ）
                case Opcodes.CHECKCAST -> new TaintValue(1, value.tainted(), value.sites(),
                        TaintValue.mayBeArray(Type.getObjectType(((TypeInsnNode) insn).desc)));
                case Opcodes.INSTANCEOF -> TaintValue.of(1, false);
                case Opcodes.GETFIELD -> fieldValue(insn);
                // 分岐・switch・return・throw・PUTSTATIC・MONITOR* は値を返さない
                default -> null;
            };
        }

        @Override
        public TaintValue binaryOperation(AbstractInsnNode insn, TaintValue value1, TaintValue value2) {
            int opcode = insn.getOpcode();
            boolean tainted = isTainted(value1) || isTainted(value2);
            return switch (opcode) {
                // 参照の要素（内側の配列を含む）は格納先と同じ類に属する。印は配列の値自体の印（split の戻り値など、
                // 作った場所の分からない乱数由来の配列）と類の印を合わせる
                case Opcodes.AALOAD -> new TaintValue(1, value1.tainted(),
                        value1.sites().isEmpty() ? Set.of(insnIndex(insn)) : value1.sites(), true);
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
            if (insn.getOpcode() == Opcodes.AASTORE && value.mayBeArray() && !value.sites().isEmpty()) {
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
                return new TaintValue(1, false, Set.of(insnIndex(insn)), true);
            }
            if (opcode == Opcodes.INVOKEDYNAMIC) {
                boolean tainted = values.stream().anyMatch(this::isTainted);
                return resultValue(insn, Type.getReturnType(((InvokeDynamicInsnNode) insn).desc), tainted);
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
                return resultValue(insn, returnType, true);
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
            return resultValue(insn, returnType, argumentTainted || receiverTainted);
        }

        /** 呼び出しの戻り値。参照型なら命令位置の場所を与える（場所の空な参照値を作らない）。 */
        private TaintValue resultValue(AbstractInsnNode insn, Type type, boolean tainted) {
            if (type.getSort() == Type.VOID) {
                return null;
            }
            if (TaintValue.isReference(type)) {
                return new TaintValue(1, tainted, Set.of(insnIndex(insn)), TaintValue.mayBeArray(type));
            }
            return TaintValue.of(type.getSize(), tainted);
        }

        /** フィールドの値。参照型なら命令位置の場所を与える（フィールドをまたぐ流れは追わず、読み出し1回ごとに別の場所）。 */
        private TaintValue fieldValue(AbstractInsnNode insn) {
            Type type = Type.getType(((FieldInsnNode) insn).desc);
            if (TaintValue.isReference(type)) {
                return new TaintValue(1, false, Set.of(insnIndex(insn)), TaintValue.mayBeArray(type));
            }
            return TaintValue.of(type.getSize(), false);
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, TaintValue value, TaintValue expected) {
            // 戻り値は追わない（メソッドをまたぐ流れは既知の限界）
        }

        @Override
        public TaintValue merge(TaintValue value1, TaintValue value2) {
            int size = value1.size() == value2.size() ? value1.size() : 1;
            boolean tainted = value1.tainted() || value2.tainted();
            boolean mayBeArray = value1.mayBeArray() || value2.mayBeArray();
            if (size == value1.size() && tainted == value1.tainted() && mayBeArray == value1.mayBeArray()
                    && value1.sites().containsAll(value2.sites())) {
                return value1;
            }
            Set<Integer> sites = new HashSet<>(value1.sites());
            sites.addAll(value2.sites());
            return new TaintValue(size, tainted, Set.copyOf(sites), mayBeArray);
        }

        /** 命令位置（0 以上）。配列を作った場所・合成の場所の識別子に使う。 */
        private int insnIndex(AbstractInsnNode insn) {
            return instructions.indexOf(insn);
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
