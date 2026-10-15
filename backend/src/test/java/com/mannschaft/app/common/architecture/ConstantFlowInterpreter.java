package com.mannschaft.app.common.architecture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;

/**
 * メソッド呼び出しの<b>実引数</b>を、定数（String / Type / int）まで遡って解決する最小のデータフロー解析器
 * （CMP-261002-1606 の番人 {@link ProductionClassImportGuardTest} 用）。
 *
 * <p>テストクラスパスには ASM の tree/analysis（{@code Analyzer}）が無く（Spring 同梱 ASM・ArchUnit 同梱 ASM
 * とも未収録）、新規依存も足さない方針のため、Spring 同梱 ASM の {@link MethodVisitor} で命令列を集め、
 * オペランドスタック・ローカル変数・配列の中身を抽象値で追う不動点計算を自前で行う。
 *
 * <p>追跡するもの: LDC 定数 → ローカル変数 → 引数、{@code ANEWARRAY}/{@code AASTORE} による配列
 * （varargs）、自プロジェクトの {@code static final} フィールドの {@code <clinit>} 初期値（配列を含む）、
 * {@code Class.forName}/{@code Class.getPackageName}/{@code Class.getName}、同一クラスの static メソッドの
 * 戻り値（定数引数で深さ {@value #MAX_CALL_DEPTH} まで展開）。分岐の合流で値が食い違えば「不明」にする。
 * 配列の別名は追わず、自メソッドで生成した配列以外への書き込みがあればそのメソッドの引数を全て「不明」にする。
 * それ以外（メソッドの戻り値・フィールド・文字列連結など）はすべて「不明」であり、利用側は不明を
 * 違反として扱う（fail closed）。
 */
final class ConstantFlowInterpreter {

    private ConstantFlowInterpreter() {
    }

    // ══════════════════════════════════════════════════════════════
    // 抽象値
    // ══════════════════════════════════════════════════════════════

    /** 抽象値。 */
    sealed interface Val permits Unknown, Const, ArrRef {
    }

    /** 定数に解決できない値。 */
    record Unknown() implements Val {
    }

    /** 定数（String / {@link Type} / Integer）。 */
    record Const(Object value) implements Val {
    }

    /** 配列への参照。中身はフレームのヒープ（割当地点 → 要素）にある。 */
    record ArrRef(int site) implements Val {
    }

    static final Val UNKNOWN = new Unknown();

    /** 中身が不明になった配列（外部へ渡った・添字が不明など）の印。 */
    private static final Val[] UNKNOWN_CONTENTS = new Val[0];

    private static final int MAX_CALL_DEPTH = 3;
    private static final String PROJECT_PREFIX = "com/mannschaft/";
    /** 配列引数を書き換えないとみなす呼び出し先（ArchUnit の取り込み API）。 */
    private static final String NON_MUTATING_OWNER_PREFIX = "com/tngtech/archunit/";

    // ══════════════════════════════════════════════════════════════
    // 公開 API
    // ══════════════════════════════════════════════════════════════

    /**
     * 呼び出し1件。{@code args} は引数ごとの定数一覧（配列引数は要素の一覧、単独値は1要素）で、
     * 定数に解決できない引数は {@code null}。
     */
    record ResolvedCall(String owner, String name, String descriptor, int line, List<List<Object>> args) {
    }

    /** フィールド宣言。 */
    record FieldDecl(int access, String name, String descriptor) {
    }

    /** 1クラス分の命令モデル。 */
    static final class ClassModel {
        final String internalName;
        final List<FieldDecl> fields = new ArrayList<>();
        final Map<String, MethodCode> methods = new HashMap<>();

        private ClassModel(String internalName) {
            this.internalName = internalName;
        }

        String className() {
            return internalName.replace('/', '.');
        }

        List<MethodCode> methods() {
            return List.copyOf(methods.values());
        }
    }

    /** バイトコードを命令モデルへ読み込む。 */
    static ClassModel parse(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassModel model = new ClassModel(reader.getClassName());
        reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                    Object value) {
                model.fields.add(new FieldDecl(access, name, descriptor));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new Collector(access, name, descriptor, model);
            }
        }, ClassReader.SKIP_FRAMES);
        return model;
    }

    /**
     * メソッド内の到達可能な全呼び出しを、実引数を解決したうえで返す（引数は全て不明から始める）。
     *
     * <p>別名解析はしない代わりに保守化する: このメソッドで生成した配列（{@code ANEWARRAY}）以外への
     * {@code AASTORE}（static 配列・合流で不明になった参照・引数や戻り値の配列への書き込み）が1つでもあれば、
     * そのメソッドの呼び出しの引数はすべて不明とする（fail closed）。
     */
    static List<ResolvedCall> calls(ClassModel cls, MethodCode method) {
        Frame[] frames = run(cls, method, null, 0).frames;
        boolean opaque = writesForeignArray(method, frames);
        List<ResolvedCall> calls = new ArrayList<>();
        for (int i = 0; i < method.insns.size(); i++) {
            Insn insn = method.insns.get(i);
            Frame frame = frames[i];
            if (frame == null || !isInvoke(insn.opcode)) {
                continue;
            }
            Type[] argTypes = Type.getArgumentTypes((String) insn.c);
            List<Val> argVals = frame.peekArgs(argTypes);
            List<List<Object>> args = new ArrayList<>();
            for (Val v : argVals) {
                args.add(opaque ? null : frame.constantsOf(v));
            }
            calls.add(new ResolvedCall((String) insn.a, (String) insn.b, (String) insn.c, insn.line, args));
        }
        return calls;
    }

    private static boolean writesForeignArray(MethodCode method, Frame[] frames) {
        for (int i = 0; i < method.insns.size(); i++) {
            Frame frame = frames[i];
            if (frame == null || method.insns.get(i).opcode != Opcodes.AASTORE) {
                continue;
            }
            Val array = frame.stack.get(frame.stack.size() - 3);
            if (!(array instanceof ArrRef ref && method.insns.get(ref.site()).opcode == Opcodes.ANEWARRAY)) {
                return true;
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // 命令モデル
    // ══════════════════════════════════════════════════════════════

    private static final int LABEL = -1;

    /** 命令1件。opcode が {@link #LABEL} のものは位置合わせ用の擬似命令。 */
    private record Insn(int opcode, Object a, Object b, Object c, int line) {
    }

    private record TryCatch(Label start, Label end, Label handler) {
    }

    /** 1メソッド分の命令列。 */
    static final class MethodCode {
        final int access;
        final String name;
        final String descriptor;
        final List<Insn> insns = new ArrayList<>();
        final List<TryCatch> tryCatches = new ArrayList<>();
        final Map<Label, Integer> labelIndex = new IdentityHashMap<>();
        int maxLocals;

        private MethodCode(int access, String name, String descriptor) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }

        String name() {
            return name;
        }

        boolean isStatic() {
            return (access & Opcodes.ACC_STATIC) != 0;
        }
    }

    private static final class Collector extends MethodVisitor {
        private final MethodCode code;
        private final ClassModel model;
        private int line;

        Collector(int access, String name, String descriptor, ClassModel model) {
            super(SpringAsmInfo.ASM_VERSION);
            this.code = new MethodCode(access, name, descriptor);
            this.model = model;
        }

        private void add(int opcode, Object a, Object b, Object c) {
            code.insns.add(new Insn(opcode, a, b, c, line));
        }

        @Override
        public void visitLineNumber(int lineNumber, Label start) {
            this.line = lineNumber;
        }

        @Override
        public void visitLabel(Label label) {
            code.labelIndex.put(label, code.insns.size());
            add(LABEL, label, null, null);
        }

        @Override
        public void visitInsn(int opcode) {
            add(opcode, null, null, null);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            add(opcode, operand, null, null);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            add(opcode, varIndex, null, null);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            add(opcode, type, null, null);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            add(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                boolean isInterface) {
            add(opcode, owner, name, descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            add(Opcodes.INVOKEDYNAMIC, "", name, descriptor);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            if (opcode == Opcodes.JSR) {
                throw new IllegalStateException("JSR は未対応: " + model.internalName + "#" + code.name);
            }
            add(opcode, label, null, null);
        }

        @Override
        public void visitLdcInsn(Object value) {
            add(Opcodes.LDC, value, null, null);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            add(Opcodes.IINC, varIndex, null, null);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            add(Opcodes.TABLESWITCH, dflt, labels, null);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            add(Opcodes.LOOKUPSWITCH, dflt, labels, null);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            add(Opcodes.MULTIANEWARRAY, numDimensions, null, null);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            code.tryCatches.add(new TryCatch(start, end, handler));
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            code.maxLocals = maxLocals;
        }

        @Override
        public void visitEnd() {
            model.methods.put(code.name + code.descriptor, code);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // フレーム
    // ══════════════════════════════════════════════════════════════

    /** ある命令の直前の抽象状態。long/double は2スロット（いずれも不明）で表す。 */
    private static final class Frame {
        final Val[] locals;
        final ArrayList<Val> stack;
        final HashMap<Integer, Val[]> heap;

        Frame(Val[] locals, ArrayList<Val> stack, HashMap<Integer, Val[]> heap) {
            this.locals = locals;
            this.stack = stack;
            this.heap = heap;
        }

        Frame copy() {
            HashMap<Integer, Val[]> heapCopy = new HashMap<>();
            heap.forEach((k, v) -> heapCopy.put(k, v == UNKNOWN_CONTENTS ? v : v.clone()));
            return new Frame(locals.clone(), new ArrayList<>(stack), heapCopy);
        }

        /** 例外ハンドラ入口の状態（スタックは例外1つ）。 */
        Frame forHandler() {
            Frame f = copy();
            f.stack.clear();
            f.stack.add(UNKNOWN);
            return f;
        }

        /** other を合流させる。変化したら true。 */
        boolean merge(Frame other) {
            boolean changed = false;
            for (int i = 0; i < locals.length; i++) {
                Val merged = mergeVal(locals[i], other.locals[i]);
                if (merged != locals[i]) {
                    locals[i] = merged;
                    changed = true;
                }
            }
            if (stack.size() != other.stack.size()) {
                throw new IllegalStateException("合流点でスタック高が一致しない");
            }
            for (int i = 0; i < stack.size(); i++) {
                Val merged = mergeVal(stack.get(i), other.stack.get(i));
                if (merged != stack.get(i)) {
                    stack.set(i, merged);
                    changed = true;
                }
            }
            for (Map.Entry<Integer, Val[]> e : other.heap.entrySet()) {
                Val[] mine = heap.get(e.getKey());
                if (mine == null) {
                    heap.put(e.getKey(), e.getValue() == UNKNOWN_CONTENTS ? e.getValue() : e.getValue().clone());
                    changed = true;
                    continue;
                }
                Val[] merged = mergeContents(mine, e.getValue());
                if (merged != mine) {
                    heap.put(e.getKey(), merged);
                    changed = true;
                }
            }
            return changed;
        }

        private static Val mergeVal(Val a, Val b) {
            return a.equals(b) || a == UNKNOWN ? a : UNKNOWN;
        }

        /** 変化がなければ a 自身を返す。 */
        private static Val[] mergeContents(Val[] a, Val[] b) {
            if (a == UNKNOWN_CONTENTS) {
                return a;
            }
            if (b == UNKNOWN_CONTENTS || a.length != b.length) {
                return UNKNOWN_CONTENTS;
            }
            Val[] result = a;
            for (int i = 0; i < a.length; i++) {
                Val merged = mergeVal(a[i], b[i]);
                if (merged != a[i]) {
                    if (result == a) {
                        result = a.clone();
                    }
                    result[i] = merged;
                }
            }
            return result;
        }

        void push(Val v) {
            stack.add(v);
        }

        void push(int slots) {
            for (int i = 0; i < slots; i++) {
                stack.add(UNKNOWN);
            }
        }

        Val pop() {
            if (stack.isEmpty()) {
                throw new IllegalStateException("スタックが空");
            }
            return stack.remove(stack.size() - 1);
        }

        void pop(int slots) {
            for (int i = 0; i < slots; i++) {
                pop();
            }
        }

        /** スタック上の引数（下から順）を、各引数の先頭スロットの値として返す（取り除かない）。 */
        List<Val> peekArgs(Type[] argTypes) {
            int slots = 0;
            for (Type t : argTypes) {
                slots += t.getSize();
            }
            int pos = stack.size() - slots;
            List<Val> vals = new ArrayList<>();
            for (Type t : argTypes) {
                vals.add(t.getSize() == 1 ? stack.get(pos) : UNKNOWN);
                pos += t.getSize();
            }
            return vals;
        }

        /** 値が外部へ渡った。配列なら以後の中身は不明。 */
        void escape(Val v) {
            if (v instanceof ArrRef ref) {
                heap.put(ref.site(), UNKNOWN_CONTENTS);
            }
        }

        /** 値を定数の一覧に解決する（配列は要素の一覧）。解決できなければ null。 */
        List<Object> constantsOf(Val v) {
            if (v instanceof Const c) {
                return List.of(c.value());
            }
            if (v instanceof ArrRef ref) {
                Val[] contents = heap.get(ref.site());
                if (contents == null || contents == UNKNOWN_CONTENTS) {
                    return null;
                }
                List<Object> values = new ArrayList<>();
                for (Val e : contents) {
                    if (!(e instanceof Const c)) {
                        return null;
                    }
                    values.add(c.value());
                }
                return values;
            }
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 不動点計算
    // ══════════════════════════════════════════════════════════════

    private record RunResult(Frame[] frames, Val returned) {
    }

    /** entryArgs が null なら引数は全て不明。 */
    private static RunResult run(ClassModel cls, MethodCode m, List<Val> entryArgs, int depth) {
        int n = m.insns.size();
        Frame[] frames = new Frame[n];
        Val returned = null;
        if (n == 0) {
            return new RunResult(frames, UNKNOWN);
        }
        Val[] locals = new Val[Math.max(m.maxLocals, 1)];
        Arrays.fill(locals, UNKNOWN);
        if (entryArgs != null) {
            int slot = m.isStatic() ? 0 : 1;
            Type[] argTypes = Type.getArgumentTypes(m.descriptor);
            for (int i = 0; i < argTypes.length; i++) {
                locals[slot] = entryArgs.get(i);
                slot += argTypes[i].getSize();
            }
        }
        frames[0] = new Frame(locals, new ArrayList<>(), new HashMap<>());

        Deque<Integer> work = new ArrayDeque<>();
        Set<Integer> queued = new HashSet<>();
        work.add(0);
        queued.add(0);
        while (!work.isEmpty()) {
            int idx = work.poll();
            queued.remove(idx);
            Insn insn = m.insns.get(idx);
            Frame in = frames[idx];
            Frame out = in.copy();
            Val ret = exec(cls, m, insn, idx, out, depth);
            if (insn.opcode == Opcodes.ARETURN) {
                returned = returned == null ? ret : Frame.mergeVal(returned, ret);
            }
            List<int[]> succ = new ArrayList<>();
            for (int s : successors(m, insn, idx)) {
                succ.add(new int[] {s, 0});
            }
            for (TryCatch tc : m.tryCatches) {
                int start = m.labelIndex.get(tc.start());
                int end = m.labelIndex.get(tc.end());
                if (idx >= start && idx < end) {
                    succ.add(new int[] {m.labelIndex.get(tc.handler()), 1});
                }
            }
            for (int[] s : succ) {
                Frame next = s[1] == 1 ? in.forHandler() : out;
                boolean changed;
                if (frames[s[0]] == null) {
                    frames[s[0]] = next.copy();
                    changed = true;
                } else {
                    changed = frames[s[0]].merge(next);
                }
                if (s[1] == 1) {
                    // 命令の実行後の状態からも例外は飛びうる
                    changed |= frames[s[0]].merge(out.forHandler());
                }
                if (changed && queued.add(s[0])) {
                    work.add(s[0]);
                }
            }
        }
        return new RunResult(frames, returned == null ? UNKNOWN : returned);
    }

    private static List<Integer> successors(MethodCode m, Insn insn, int idx) {
        int op = insn.opcode;
        List<Integer> next = new ArrayList<>();
        switch (op) {
            case Opcodes.GOTO -> next.add(m.labelIndex.get((Label) insn.a));
            case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> {
                next.add(m.labelIndex.get((Label) insn.a));
                for (Label l : (Label[]) insn.b) {
                    next.add(m.labelIndex.get(l));
                }
            }
            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN,
                    Opcodes.RETURN, Opcodes.ATHROW, Opcodes.RET -> {
            }
            default -> {
                if (isConditionalJump(op)) {
                    next.add(m.labelIndex.get((Label) insn.a));
                }
                if (idx + 1 < m.insns.size()) {
                    next.add(idx + 1);
                }
            }
        }
        return next;
    }

    private static boolean isConditionalJump(int op) {
        return (op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE) || op == Opcodes.IFNULL
                || op == Opcodes.IFNONNULL;
    }

    private static boolean isInvoke(int op) {
        return op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKESTATIC
                || op == Opcodes.INVOKEINTERFACE || op == Opcodes.INVOKEDYNAMIC;
    }

    /** 1命令を f に適用する。ARETURN のときは返す値を返す。 */
    private static Val exec(ClassModel cls, MethodCode m, Insn insn, int idx, Frame f, int depth) {
        int op = insn.opcode;
        switch (op) {
            case LABEL, Opcodes.NOP, Opcodes.GOTO, Opcodes.RETURN -> {
            }
            case Opcodes.ACONST_NULL -> f.push(UNKNOWN);
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
                    Opcodes.ICONST_4, Opcodes.ICONST_5 -> f.push(new Const(op - Opcodes.ICONST_0));
            case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> f.push(2);
            case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> f.push(1);
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> f.push(new Const(insn.a));
            case Opcodes.LDC -> {
                Object v = insn.a;
                if (v instanceof Long || v instanceof Double) {
                    f.push(2);
                } else if (v instanceof String || v instanceof Integer
                        || (v instanceof Type t && t.getSort() != Type.METHOD)) {
                    f.push(new Const(v));
                } else {
                    f.push(UNKNOWN);
                }
            }
            case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> f.push(f.locals[(Integer) insn.a]);
            case Opcodes.LLOAD, Opcodes.DLOAD -> f.push(2);
            case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> f.locals[(Integer) insn.a] = f.pop();
            case Opcodes.LSTORE, Opcodes.DSTORE -> {
                f.pop(2);
                f.locals[(Integer) insn.a] = UNKNOWN;
                f.locals[(Integer) insn.a + 1] = UNKNOWN;
            }
            case Opcodes.IINC -> f.locals[(Integer) insn.a] = UNKNOWN;
            case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> {
                f.pop(2);
                f.push(1);
            }
            case Opcodes.LALOAD, Opcodes.DALOAD -> {
                f.pop(2);
                f.push(2);
            }
            case Opcodes.AALOAD -> {
                Val index = f.pop();
                Val array = f.pop();
                f.push(element(f, array, index));
            }
            case Opcodes.IASTORE, Opcodes.FASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> f.pop(3);
            case Opcodes.LASTORE, Opcodes.DASTORE -> f.pop(4);
            case Opcodes.AASTORE -> {
                Val value = f.pop();
                Val index = f.pop();
                Val array = f.pop();
                f.escape(value);
                store(f, array, index, value);
            }
            case Opcodes.POP -> f.pop(1);
            case Opcodes.POP2 -> f.pop(2);
            case Opcodes.DUP -> {
                Val v = f.pop();
                f.push(v);
                f.push(v);
            }
            case Opcodes.DUP_X1 -> {
                Val v1 = f.pop();
                Val v2 = f.pop();
                pushAll(f, v1, v2, v1);
            }
            case Opcodes.DUP_X2 -> {
                Val v1 = f.pop();
                Val v2 = f.pop();
                Val v3 = f.pop();
                pushAll(f, v1, v3, v2, v1);
            }
            case Opcodes.DUP2 -> {
                Val v1 = f.pop();
                Val v2 = f.pop();
                pushAll(f, v2, v1, v2, v1);
            }
            case Opcodes.DUP2_X1 -> {
                Val v1 = f.pop();
                Val v2 = f.pop();
                Val v3 = f.pop();
                pushAll(f, v2, v1, v3, v2, v1);
            }
            case Opcodes.DUP2_X2 -> {
                Val v1 = f.pop();
                Val v2 = f.pop();
                Val v3 = f.pop();
                Val v4 = f.pop();
                pushAll(f, v2, v1, v4, v3, v2, v1);
            }
            case Opcodes.SWAP -> {
                Val v1 = f.pop();
                Val v2 = f.pop();
                pushAll(f, v1, v2);
            }
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.IAND,
                    Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.FADD,
                    Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM, Opcodes.FCMPL, Opcodes.FCMPG -> {
                f.pop(2);
                f.push(1);
            }
            case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LAND,
                    Opcodes.LOR, Opcodes.LXOR, Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV,
                    Opcodes.DREM -> {
                f.pop(4);
                f.push(2);
            }
            case Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> {
                f.pop(3);
                f.push(2);
            }
            case Opcodes.INEG, Opcodes.FNEG, Opcodes.I2F, Opcodes.F2I, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S,
                    Opcodes.ARRAYLENGTH, Opcodes.INSTANCEOF -> {
                f.pop(1);
                f.push(1);
            }
            case Opcodes.LNEG, Opcodes.DNEG, Opcodes.L2D, Opcodes.D2L -> {
                f.pop(2);
                f.push(2);
            }
            case Opcodes.I2L, Opcodes.I2D, Opcodes.F2L, Opcodes.F2D -> {
                f.pop(1);
                f.push(2);
            }
            case Opcodes.L2I, Opcodes.L2F, Opcodes.D2I, Opcodes.D2F -> {
                f.pop(2);
                f.push(1);
            }
            case Opcodes.LCMP, Opcodes.DCMPL, Opcodes.DCMPG -> {
                f.pop(4);
                f.push(1);
            }
            case Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ATHROW, Opcodes.MONITORENTER,
                    Opcodes.MONITOREXIT -> f.pop(1);
            case Opcodes.ARETURN -> {
                Val v = f.pop();
                f.escape(v);
                return v instanceof ArrRef ? UNKNOWN : v;
            }
            case Opcodes.LRETURN, Opcodes.DRETURN -> f.pop(2);
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                    Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> f.pop(1);
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                    Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> f.pop(2);
            case Opcodes.NEW -> f.push(UNKNOWN);
            case Opcodes.NEWARRAY -> {
                f.pop(1);
                f.push(UNKNOWN);
            }
            case Opcodes.ANEWARRAY -> {
                Val length = f.pop();
                if (length instanceof Const c && c.value() instanceof Integer len && len >= 0 && len < 10_000) {
                    Val[] contents = new Val[len];
                    Arrays.fill(contents, UNKNOWN);
                    f.heap.put(idx, contents);
                } else {
                    f.heap.put(idx, UNKNOWN_CONTENTS);
                }
                f.push(new ArrRef(idx));
            }
            case Opcodes.CHECKCAST -> {
                // 値はそのまま
            }
            case Opcodes.MULTIANEWARRAY -> {
                f.pop((Integer) insn.a);
                f.push(UNKNOWN);
            }
            case Opcodes.GETSTATIC -> getStatic(f, insn, idx);
            case Opcodes.PUTSTATIC -> {
                int size = Type.getType((String) insn.c).getSize();
                if (size == 1) {
                    f.escape(f.pop());
                } else {
                    f.pop(2);
                }
            }
            case Opcodes.GETFIELD -> {
                f.pop(1);
                f.push(Type.getType((String) insn.c).getSize());
            }
            case Opcodes.PUTFIELD -> {
                int size = Type.getType((String) insn.c).getSize();
                if (size == 1) {
                    f.escape(f.pop());
                } else {
                    f.pop(2);
                }
                f.pop(1);
            }
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE,
                    Opcodes.INVOKEDYNAMIC -> invoke(cls, insn, f, depth);
            default -> throw new IllegalStateException(
                    "未対応の命令 opcode=" + op + ": " + cls.internalName + "#" + m.name);
        }
        return null;
    }

    private static void pushAll(Frame f, Val... vals) {
        for (Val v : vals) {
            f.push(v);
        }
    }

    private static Val element(Frame f, Val array, Val index) {
        if (array instanceof ArrRef ref && index instanceof Const c && c.value() instanceof Integer i) {
            Val[] contents = f.heap.get(ref.site());
            if (contents != null && contents != UNKNOWN_CONTENTS && i >= 0 && i < contents.length) {
                return contents[i];
            }
        }
        return UNKNOWN;
    }

    private static void store(Frame f, Val array, Val index, Val value) {
        if (!(array instanceof ArrRef ref)) {
            return;
        }
        Val[] contents = f.heap.get(ref.site());
        if (contents == null || contents == UNKNOWN_CONTENTS) {
            return;
        }
        if (index instanceof Const c && c.value() instanceof Integer i && i >= 0 && i < contents.length) {
            contents[i] = value instanceof ArrRef ? UNKNOWN : value;
        } else {
            f.heap.put(ref.site(), UNKNOWN_CONTENTS);
        }
    }

    private static void getStatic(Frame f, Insn insn, int idx) {
        String owner = (String) insn.a;
        String desc = (String) insn.c;
        int size = Type.getType(desc).getSize();
        if (size == 2 || !owner.startsWith(PROJECT_PREFIX)) {
            f.push(size);
            return;
        }
        StaticValue sv = StaticConstants.of(owner).get((String) insn.b);
        if (sv == null) {
            f.push(UNKNOWN);
        } else if (sv.array() != null) {
            f.heap.put(idx, sv.array().clone());
            f.push(new ArrRef(idx));
        } else {
            f.push(sv.scalar());
        }
    }

    private static void invoke(ClassModel cls, Insn insn, Frame f, int depth) {
        String owner = (String) insn.a;
        String name = (String) insn.b;
        String desc = (String) insn.c;
        Type[] argTypes = Type.getArgumentTypes(desc);
        List<Val> args = f.peekArgs(argTypes);
        int argSlots = 0;
        for (Type t : argTypes) {
            argSlots += t.getSize();
        }
        f.pop(argSlots);
        Val receiver = null;
        if (insn.opcode != Opcodes.INVOKESTATIC && insn.opcode != Opcodes.INVOKEDYNAMIC) {
            receiver = f.pop();
        }
        Type returnType = Type.getReturnType(desc);

        Val result = UNKNOWN;
        if ("java/lang/Class".equals(owner)) {
            result = classMethod(name, desc, receiver, args);
        } else if (insn.opcode == Opcodes.INVOKESTATIC && owner.equals(cls.internalName)
                && depth < MAX_CALL_DEPTH) {
            MethodCode callee = cls.methods.get(name + desc);
            if (callee != null) {
                List<Val> calleeArgs = new ArrayList<>();
                for (Val a : args) {
                    calleeArgs.add(a instanceof ArrRef ? UNKNOWN : a);
                }
                result = run(cls, callee, calleeArgs, depth + 1).returned();
            }
        }
        if (!owner.startsWith(NON_MUTATING_OWNER_PREFIX)) {
            for (Val a : args) {
                f.escape(a);
            }
            if (receiver != null) {
                f.escape(receiver);
            }
        }
        if (returnType.getSize() == 1) {
            f.push(result);
        } else {
            f.push(returnType.getSize());
        }
    }

    private static Val classMethod(String name, String desc, Val receiver, List<Val> args) {
        if ("forName".equals(name) && "(Ljava/lang/String;)Ljava/lang/Class;".equals(desc)
                && args.get(0) instanceof Const c && c.value() instanceof String fqcn) {
            return new Const(Type.getObjectType(fqcn.replace('.', '/')));
        }
        if (receiver instanceof Const c && c.value() instanceof Type type && type.getSort() == Type.OBJECT) {
            String className = type.getClassName();
            if ("getName".equals(name) && "()Ljava/lang/String;".equals(desc)) {
                return new Const(className);
            }
            if ("getPackageName".equals(name) && "()Ljava/lang/String;".equals(desc)) {
                int lastDot = className.lastIndexOf('.');
                return new Const(lastDot < 0 ? "" : className.substring(0, lastDot));
            }
        }
        return UNKNOWN;
    }

    // ══════════════════════════════════════════════════════════════
    // static final フィールドの初期値
    // ══════════════════════════════════════════════════════════════

    /** static フィールドの初期値。配列なら array、そうでなければ scalar。 */
    private record StaticValue(Val scalar, Val[] array) {
    }

    /** 自プロジェクトの static final フィールドの {@code <clinit>} 初期値を、クラス単位でキャッシュする。 */
    private static final class StaticConstants {

        private static final Map<String, Map<String, StaticValue>> CACHE = new HashMap<>();
        private static final Set<String> IN_PROGRESS = new HashSet<>();

        static synchronized Map<String, StaticValue> of(String ownerInternalName) {
            Map<String, StaticValue> cached = CACHE.get(ownerInternalName);
            if (cached != null) {
                return cached;
            }
            if (!IN_PROGRESS.add(ownerInternalName)) {
                // 初期化の循環。解決できないものとして扱う（fail closed）
                return Map.of();
            }
            try {
                Map<String, StaticValue> parsed = parse(ownerInternalName);
                CACHE.put(ownerInternalName, parsed);
                return parsed;
            } finally {
                IN_PROGRESS.remove(ownerInternalName);
            }
        }

        private static Map<String, StaticValue> parse(String ownerInternalName) {
            byte[] bytes = readResource(ownerInternalName + ".class");
            if (bytes == null) {
                return Map.of();
            }
            ClassModel model = ConstantFlowInterpreter.parse(bytes);
            Set<String> finalStatics = new HashSet<>();
            for (FieldDecl field : model.fields) {
                if ((field.access() & Opcodes.ACC_STATIC) != 0 && (field.access() & Opcodes.ACC_FINAL) != 0) {
                    finalStatics.add(field.name());
                }
            }
            MethodCode clinit = model.methods.get("<clinit>()V");
            if (clinit == null) {
                return Map.of();
            }
            Frame[] frames = run(model, clinit, List.of(), 0).frames();
            Map<String, StaticValue> values = new HashMap<>();
            Set<String> conflicting = new HashSet<>();
            for (int i = 0; i < clinit.insns.size(); i++) {
                Insn insn = clinit.insns.get(i);
                Frame frame = frames[i];
                if (frame == null || insn.opcode != Opcodes.PUTSTATIC || !ownerInternalName.equals(insn.a)
                        || !finalStatics.contains((String) insn.b)) {
                    continue;
                }
                String field = (String) insn.b;
                Val top = frame.stack.get(frame.stack.size() - 1);
                StaticValue value;
                if (top instanceof ArrRef ref) {
                    Val[] contents = frame.heap.get(ref.site());
                    value = contents == null || contents == UNKNOWN_CONTENTS
                            ? new StaticValue(UNKNOWN, null)
                            : new StaticValue(null, contents.clone());
                } else {
                    value = new StaticValue(top, null);
                }
                if (values.containsKey(field)) {
                    conflicting.add(field);
                }
                values.put(field, value);
            }
            conflicting.forEach(field -> values.put(field, new StaticValue(UNKNOWN, null)));
            return values;
        }
    }

    static byte[] readResource(String resourceName) {
        try (InputStream in = ConstantFlowInterpreter.class.getClassLoader().getResourceAsStream(resourceName)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
