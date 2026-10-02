package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mannschaft.app.common.architecture.AuthzGateEffectivenessAuditTest.GateVocabulary;
import com.mannschaft.app.common.architecture.AuthzGateEffectivenessAuditTest.MethodDecl;
import com.mannschaft.app.common.architecture.AuthzGateEffectivenessAuditTest.Src;

/**
 * 認可判定(DECISION)の PROPAGATE 棚卸し（認可根治・裏目付戦役 測量タスク）。
 *
 * <h2>この番人が保証すること・しないこと</h2>
 * <p>{@link AuthzGateEffectivenessAuditTest}（形②）は、DECISION ゲートの戻り値の到達先を
 * GATE / PROPAGATE / DTO_SINK の 3 分類へ振り分け、<b>DTO_SINK のみを違反候補</b>とする。
 * PROPAGATE は javadoc L124-128 の通り「下流が enforce しているか検証しない」ため
 * <b>保守的に合格</b>させている（偽陰性を許容）。本クラスは違反判定を一切行わず、
 * その PROPAGATE 判定がどのソース箇所に何件あるかを<b>列挙するだけ</b>の測量番人である。
 * PROPAGATE の定義（形②の判定ロジック内）:</p>
 * <ul>
 *   <li>{@code return v} / {@code return f(v)} → <b>PROPAGATE</b>（判定は呼び元へ委ねられる）
 *       （{@link AuthzGateEffectivenessAuditTest} L109 相当。以下「return形」）</li>
 *   <li>小文字始まりメソッドの引数 → <b>PROPAGATE</b>（下流が enforce する可能性）
 *       （同 L110 相当。以下「委譲形」）</li>
 * </ul>
 * <p>走査部品（マスク処理・ゲートクラス判定・メソッドパーサ・ゲート語彙収集・レシーバ識別子抽出）は
 * {@link AuthzGateReturnValueGuardTest#mask(String)} /
 * {@link AuthzGateReturnValueGuardTest#isGateClassFile(String)} /
 * {@link AuthzGateEffectivenessAuditTest#parseMethods(String)} /
 * {@link AuthzGateEffectivenessAuditTest#receiverIdentifiers(String, Pattern)} /
 * {@link AuthzGateEffectivenessAuditTest.GateVocabulary} をそのまま流用し、
 * 判定の正準を二重実装しない（{@link AuthzGateEffectivenessAuditTest} は編集しない）。</p>
 *
 * <p>「同一変数の使用箇所を GATE/PROPAGATE/DTO_SINK のどれに分類するか」の走査ロジックそのもの
 * （{@code onlyFlowsIntoDto} 相当の判定式）は {@code private} のため呼び出せず、本クラス内で
 * 同じ判定基準を再実装している（GATE 語彙・条件式パターンは形②の javadoc L106-112 と同一）。</p>
 */
class AuthzPropagateInventoryTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");
    private static final Path REPORT_PATH =
        Paths.get("build", "reports", "authz-propagate-inventory.txt");

    private static final Pattern QUALIFIED_CALL = Pattern.compile(
        "(?<![\\w$.])([A-Za-z_$][\\w$]*)\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\(");

    private static final Set<String> JAVA_KEYWORDS = Set.of(
        "extends", "implements", "throws", "return", "new", "instanceof", "public", "private",
        "protected", "static", "final", "abstract", "if", "for", "while", "switch", "catch",
        "synchronized", "try", "else", "do", "case", "default", "this", "super",
        "assert", "yield", "finally", "throw");

    private static final Pattern DTO_SINK_CALLEE =
        Pattern.compile("^(?:of|from|builder|build|create)$");

    /** ロガー呼び出しの受け口識別子（{@code log.warn(...)} 等）。認可の下流委譲ではない。 */
    private static final Pattern LOGGER_RECEIVER = Pattern.compile("(?i)^(?:log|logger)$");

    /** ロガーのレベルメソッド名。上記レシーバと組み合わせて委譲候補から除外する。 */
    private static final Pattern LOGGER_METHOD =
        Pattern.compile("^(?:trace|debug|info|warn|error)$");

    // ═══════════════════════════════════════════════════════════════════════
    // 棚卸し本体
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PROPAGATE 箇所を棚卸しし、ファイル出力する")
    void PROPAGATE箇所を棚卸しする() throws IOException {
        List<Src> sources = loadAllSources();
        List<PropagateEntry> entries = scan(sources);

        assertFalse(entries.isEmpty(),
            "PROPAGATE 箇所が 0 件です。走査ロジックが壊れて空虚化した疑いがあります"
                + "（AuthzGateEffectivenessAuditTest の形②で PROPAGATE を保守的合格させている"
                + "既知の箇所が実在するため、0 件は不自然です）。");

        writeReport(entries);

        long returnForm = entries.stream().filter(e -> e.kind == Kind.RETURN_FORM).count();
        long delegateForm = entries.stream().filter(e -> e.kind == Kind.DELEGATE_FORM).count();
        System.out.println("[AuthzPropagateInventory] PROPAGATE 総件数: " + entries.size()
            + "（return形: " + returnForm + " / 委譲形: " + delegateForm + "）"
            + " → " + REPORT_PATH.toAbsolutePath());
    }

    enum Kind { RETURN_FORM, DELEGATE_FORM }

    static final class PropagateEntry {
        final String relPath;
        final int line;
        final String className;
        final String methodName;
        final Kind kind;
        /** 委譲形のときの委譲先メソッド名（return形は null）。 */
        final String calleeName;
        final String snippet;

        PropagateEntry(String relPath, int line, String className, String methodName, Kind kind,
                String calleeName, String snippet) {
            this.relPath = relPath;
            this.line = line;
            this.className = className;
            this.methodName = methodName;
            this.kind = kind;
            this.calleeName = calleeName;
            this.snippet = snippet;
        }
    }

    /** 走査本体（実ファイル走査と fixture 自己検証が同一コアを通る）。 */
    static List<PropagateEntry> scan(List<Src> sources) {
        GateVocabulary vocab = GateVocabulary.from(sources);
        List<PropagateEntry> out = new ArrayList<>();
        if (vocab.isEmpty()) {
            return out;
        }
        for (Src s : sources) {
            out.addAll(scanOne(s, vocab));
        }
        return out;
    }

    private static List<PropagateEntry> scanOne(Src s, GateVocabulary vocab) {
        String masked = s.masked();
        Set<String> receivers = AuthzGateEffectivenessAuditTest.receiverIdentifiers(
            masked, vocab.receiverPattern);
        List<PropagateEntry> out = new ArrayList<>();
        if (receivers.isEmpty()) {
            return out;
        }
        List<MethodDecl> methods = AuthzGateEffectivenessAuditTest.parseMethods(masked);
        Matcher m = QUALIFIED_CALL.matcher(masked);
        while (m.find()) {
            String recv = m.group(1);
            String method = m.group(2);
            if (!receivers.contains(recv) || !vocab.decisionMethods.contains(method)) {
                continue;
            }
            int recvStart = m.start(1);
            MethodDecl enclosing = enclosingMethod(methods, recvStart);
            if (enclosing == null) {
                continue;
            }
            int open = masked.indexOf('(', m.end() - 1);
            int close = matchParen(masked, open);
            if (close < 0) {
                continue;
            }
            String var = assignedVariable(masked, recvStart);
            if (var == null) {
                continue; // 代入以外（return f(gate(..)) の直接受け渡し等）は今回の棚卸し対象外
            }
            int blockEnd = enclosingBlockEnd(masked, recvStart);
            if (blockEnd < 0) {
                continue;
            }
            out.addAll(classifyUsages(s, masked, close + 1, blockEnd, var, enclosing));
        }
        return out;
    }

    /** {@code var} の使用箇所を分類し、PROPAGATE（return形／委譲形）のみエントリ化する。 */
    private static List<PropagateEntry> classifyUsages(Src s, String masked, int from, int to,
            String var, MethodDecl enclosing) {
        List<PropagateEntry> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(var) + "(?![\\w$])")
            .matcher(masked).region(Math.min(from, to), Math.max(from, to));
        while (m.find()) {
            int pos = m.start();
            String before = masked.substring(Math.max(0, pos - 80), pos);
            String after = masked.substring(m.end(), Math.min(masked.length(), m.end() + 40));
            // GATE・条件式・throw は棚卸し対象外（形②と同じ除外基準）
            if (before.matches("(?s).*\\b(?:if|while)\\s*\\([^()]*$")
                || before.matches("(?s).*(?:&&|\\|\\|)\\s*!?\\s*$")
                || after.matches("(?s)^\\s*(?:&&|\\|\\||\\?).*")
                || before.matches("(?s).*\\.(?:filter|anyMatch|allMatch|noneMatch|removeIf|takeWhile)\\s*\\([^()]*$")
                || before.matches("(?s).*\\bthrow\\b[^;]*$")) {
                continue;
            }
            // PROPAGATE: return v; / return f(.. v ..)
            if (before.matches("(?s).*\\breturn\\s*$")) {
                int line = lineOf(s.content, pos);
                out.add(new PropagateEntry(s.relPath, line, simpleName(s.relPath), enclosing.name,
                    Kind.RETURN_FORM, null, snippet(s.content, statementStart(masked, pos),
                        lineEnd(masked, pos))));
                continue;
            }
            String callee = enclosingCallee(masked, pos);
            if (callee == null) {
                continue; // 宣言行など
            }
            // Java キーワード（switch/if/for 等）は委譲先メソッドではない。
            // 例: `switch (roleName.toUpperCase()) { case "ADMIN" -> ... }` の
            // roleName は switch 文の対象式に現れるだけで、switch という名の
            // メソッドへ委譲しているわけではない（switch は予約語でメソッド名になり得ない）。
            if (JAVA_KEYWORDS.contains(callee)) {
                continue;
            }
            boolean dtoSink = callee.startsWith("new ")
                || Character.isUpperCase(callee.charAt(0))
                || DTO_SINK_CALLEE.matcher(callee).matches();
            if (dtoSink) {
                continue; // DTO_SINK は本測量の対象外（形②の担当）
            }
            // ロガー呼び出し（log.warn(...) 等）は認可の下流委譲ではなく、
            // 単に判定結果／ロール名等をログへ出力しているだけ。
            String calleeReceiver = enclosingCallReceiver(masked, pos);
            if (calleeReceiver != null && LOGGER_RECEIVER.matcher(calleeReceiver).matches()
                    && LOGGER_METHOD.matcher(callee).matches()) {
                continue;
            }
            // PROPAGATE: 小文字始まりメソッドへの委譲
            int line = lineOf(s.content, pos);
            out.add(new PropagateEntry(s.relPath, line, simpleName(s.relPath), enclosing.name,
                Kind.DELEGATE_FORM, callee, snippet(s.content, statementStart(masked, pos),
                    lineEnd(masked, pos))));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ローカル走査ヘルパ（対応する private ロジックが AuthzGateEffectivenessAuditTest 側で
    // private のため呼び出せず、同一判定基準で再実装したもの。既存クラスは編集していない）
    // ═══════════════════════════════════════════════════════════════════════

    private static MethodDecl enclosingMethod(List<MethodDecl> methods, int pos) {
        for (MethodDecl d : methods) {
            if (d.bodyStart <= pos && pos <= d.bodyEnd) {
                return d;
            }
        }
        return null;
    }

    private static String assignedVariable(String masked, int recvStart) {
        int p = skipWsBack(masked, recvStart - 1);
        if (p < 0 || masked.charAt(p) != '=') {
            return null;
        }
        char prev = p > 0 ? masked.charAt(p - 1) : ' ';
        if (prev == '=' || prev == '!' || prev == '<' || prev == '>' || prev == '+' || prev == '-') {
            return null;
        }
        int e = skipWsBack(masked, p - 1);
        if (e < 0 || !isIdentPart(masked.charAt(e))) {
            return null;
        }
        int st = e;
        while (st > 0 && isIdentPart(masked.charAt(st - 1))) {
            st--;
        }
        String name = masked.substring(st, e + 1);
        return JAVA_KEYWORDS.contains(name) ? null : name;
    }

    private static int enclosingBlockEnd(String masked, int pos) {
        int depth = 0;
        int open = -1;
        for (int i = pos - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth == 0) {
                    open = i;
                    break;
                }
                depth--;
            }
        }
        if (open < 0) {
            return -1;
        }
        return matchBrace(masked, open);
    }

    private static String enclosingCallee(String masked, int pos) {
        int depth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    int e = skipWsBack(masked, i - 1);
                    if (e < 0 || !isIdentPart(masked.charAt(e))) {
                        return null;
                    }
                    int st = e;
                    while (st > 0 && isIdentPart(masked.charAt(st - 1))) {
                        st--;
                    }
                    String name = masked.substring(st, e + 1);
                    String pre = masked.substring(Math.max(0, st - 8), st);
                    return pre.matches("(?s).*\\bnew\\s*$") ? "new " + name : name;
                }
                depth--;
            } else if ((c == ';' || c == '{' || c == '}') && depth == 0) {
                return null;
            }
        }
        return null;
    }

    /**
     * {@code pos} を囲む直近の呼び出し式のレシーバ識別子（{@code recv.method(...)} の
     * {@code recv}）を返す。非修飾呼び出し（{@code method(...)}）やレシーバがメソッド呼び出し
     * ・フィールドチェーン等の複雑な式の場合は null（ロガー判定にのみ使う軽量版のため）。
     */
    private static String enclosingCallReceiver(String masked, int pos) {
        int depth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    int e = skipWsBack(masked, i - 1);
                    if (e < 0 || !isIdentPart(masked.charAt(e))) {
                        return null;
                    }
                    int st = e;
                    while (st > 0 && isIdentPart(masked.charAt(st - 1))) {
                        st--;
                    }
                    int dot = skipWsBack(masked, st - 1);
                    if (dot < 0 || masked.charAt(dot) != '.') {
                        return null; // 非修飾呼び出し
                    }
                    int re = skipWsBack(masked, dot - 1);
                    if (re < 0 || !isIdentPart(masked.charAt(re))) {
                        return null;
                    }
                    int rst = re;
                    while (rst > 0 && isIdentPart(masked.charAt(rst - 1))) {
                        rst--;
                    }
                    return masked.substring(rst, re + 1);
                }
                depth--;
            } else if ((c == ';' || c == '{' || c == '}') && depth == 0) {
                return null;
            }
        }
        return null;
    }

    private static boolean isIdentPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    private static int skipWsBack(String s, int i) {
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int matchParen(String s, int open) {
        if (open < 0 || open >= s.length() || s.charAt(open) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int statementStart(String masked, int pos) {
        int depth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '(' || c == '[') {
                if (depth == 0) {
                    return i + 1;
                }
                depth--;
            } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int lineEnd(String masked, int pos) {
        int i = masked.indexOf(';', pos);
        return i < 0 ? Math.min(pos + 80, masked.length() - 1) : i;
    }

    private static int lineOf(String src, int offset) {
        int line = 1;
        int limit = Math.min(offset, src.length());
        for (int i = 0; i < limit; i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String snippet(String src, int from, int to) {
        int a = Math.max(0, Math.min(from, src.length()));
        int b = Math.max(a, Math.min(to + 1, src.length()));
        return src.substring(a, b).replaceAll("\\s+", " ").trim();
    }

    private static String simpleName(String relPath) {
        String base = relPath.substring(relPath.lastIndexOf('/') + 1);
        return base.endsWith(".java") ? base.substring(0, base.length() - ".java".length()) : base;
    }

    private static void writeReport(List<PropagateEntry> entries) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("認可 PROPAGATE 棚卸し（自動生成。再生成: ")
            .append("./gradlew test --tests \"*AuthzPropagateInventoryTest\"）\n");
        sb.append("総件数: ").append(entries.size()).append('\n');
        long returnForm = entries.stream().filter(e -> e.kind == Kind.RETURN_FORM).count();
        long delegateForm = entries.stream().filter(e -> e.kind == Kind.DELEGATE_FORM).count();
        sb.append("  return形: ").append(returnForm).append('\n');
        sb.append("  委譲形  : ").append(delegateForm).append('\n');
        sb.append('\n');
        for (PropagateEntry e : entries) {
            sb.append(e.className).append('#').append(e.methodName)
                .append("  ").append(e.relPath).append(':').append(e.line)
                .append("  [").append(e.kind).append(']')
                .append(e.calleeName != null ? "  → " + e.calleeName : "")
                .append("  ").append(e.snippet).append('\n');
        }
        Files.writeString(REPORT_PATH, sb.toString(), StandardCharsets.UTF_8);
    }

    private static List<Src> loadAllSources() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT),
            "ソースルートが見つかりません: " + SOURCE_ROOT.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");
        List<Src> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        out.add(new Src(p.toString().replace('\\', '/'),
                            Files.readString(p, StandardCharsets.UTF_8)));
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
        }
        assertFalse(out.isEmpty(), "走査対象の .java が 0 件です（パーサ空虚化の疑い）");
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // パーサ自己検証（fixture）
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("パーサ自己検証（fixture）")
    class パーサ自己検証 {

        private final Src gate = new Src(
            "src/main/java/com/mannschaft/app/demo/service/DemoAccessService.java",
            """
            package com.mannschaft.app.demo.service;
            public class DemoAccessService {
                public boolean isAdminOrAbove(Long userId, Long scopeId, String scopeType) { return false; }
                public String getRoleName(Long userId, Long scopeId, String scopeType) { return null; }
            }
            """);

        @Test
        @DisplayName("p: return v 形は return形 PROPAGATE として検出される")
        void p_return形は検出される() {
            Src caller = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoReturnResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoReturnResolver {
                    private final DemoAccessService accessControlService;
                    DemoReturnResolver(DemoAccessService s) { this.accessControlService = s; }
                    public Boolean resolve(Long userId, Long id) {
                        boolean canEdit = accessControlService.isAdminOrAbove(userId, id, "TEAM");
                        return canEdit;
                    }
                }
                """);
            List<PropagateEntry> entries = scan(Arrays.asList(gate, caller));
            assertTrue(entries.stream().anyMatch(e -> e.kind == Kind.RETURN_FORM),
                "return v 形は return形 PROPAGATE として検出されるべき: " + entries.size());
        }

        @Test
        @DisplayName("q: 小文字始まりメソッドへの委譲は委譲形 PROPAGATE として検出される")
        void q_委譲形は検出される() {
            Src caller = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoDelegateResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoDelegateResolver {
                    private final DemoAccessService accessControlService;
                    DemoDelegateResolver(DemoAccessService s) { this.accessControlService = s; }
                    public Object resolve(Long userId, Long id) {
                        String roleName = accessControlService.getRoleName(userId, id, "TEAM");
                        return downstreamService.getPage(id, userId, roleName);
                    }
                }
                """);
            List<PropagateEntry> entries = scan(Arrays.asList(gate, caller));
            assertTrue(entries.stream().anyMatch(
                e -> e.kind == Kind.DELEGATE_FORM && "getPage".equals(e.calleeName)),
                "小文字始まりメソッドへの委譲は委譲形 PROPAGATE として検出されるべき: " + entries.size());
        }

        @Test
        @DisplayName("r: DTO 構築（GATE/DTO_SINK）は PROPAGATE として検出されない")
        void r_DTO_SINKは検出されない() {
            Src caller = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoDtoSinkResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoDtoSinkResolver {
                    private final DemoAccessService accessControlService;
                    DemoDtoSinkResolver(DemoAccessService s) { this.accessControlService = s; }
                    public Object resolve(Long userId, Long id) {
                        boolean canEdit = accessControlService.isAdminOrAbove(userId, id, "TEAM");
                        return new DemoMetaDto(id, "name", canEdit);
                    }
                }
                """);
            List<PropagateEntry> entries = scan(Arrays.asList(gate, caller));
            assertTrue(entries.isEmpty(),
                "DTO 構築のみに流れる形は PROPAGATE として検出されないべき: " + entries.size());
        }

        @Test
        @DisplayName("s: if で打ち切る形（GATE）は PROPAGATE として検出されない")
        void s_GATEは検出されない() {
            Src caller = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoGateResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoGateResolver {
                    private final DemoAccessService accessControlService;
                    DemoGateResolver(DemoAccessService s) { this.accessControlService = s; }
                    public Object resolve(Long userId, Long id) {
                        boolean canEdit = accessControlService.isAdminOrAbove(userId, id, "TEAM");
                        if (!canEdit) { throw new RuntimeException(); }
                        return new DemoMetaDto(id, "name", canEdit);
                    }
                }
                """);
            List<PropagateEntry> entries = scan(Arrays.asList(gate, caller));
            assertTrue(entries.isEmpty(),
                "if で打ち切る GATE 形は PROPAGATE として検出されないべき: " + entries.size());
        }

        @Test
        @DisplayName("t: アクセス修飾子の無い（package-private）メソッド内の PROPAGATE も検出される")
        void t_package_private_メソッドも検出される() {
            Src caller = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoPackagePrivatePropagateResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                class DemoPackagePrivatePropagateResolver {
                    private final DemoAccessService accessControlService;
                    DemoPackagePrivatePropagateResolver(DemoAccessService s) {
                        this.accessControlService = s;
                    }
                    Boolean resolve(Long userId, Long id) {
                        boolean canEdit = accessControlService.isAdminOrAbove(userId, id, "TEAM");
                        return canEdit;
                    }
                }
                """);
            List<PropagateEntry> entries = scan(Arrays.asList(gate, caller));
            assertTrue(entries.stream().anyMatch(e -> e.kind == Kind.RETURN_FORM
                    && "DemoPackagePrivatePropagateResolver".equals(e.className)),
                "package-private メソッド内の PROPAGATE も検出されるべき: " + entries.size());
        }

        // ── ノイズ除去 ①: Java キーワード（switch 等）は委譲先メソッドではない ──────────

        @Test
        @DisplayName("u: switch 文の対象式に現れるだけの変数は委譲形 PROPAGATE として検出されない")
        void u_switch文の対象式は検出されない() {
            Src caller = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoSwitchResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoSwitchResolver {
                    private final DemoAccessService accessControlService;
                    DemoSwitchResolver(DemoAccessService s) { this.accessControlService = s; }
                    public void resolve(Long userId, Long id) {
                        String roleName = accessControlService.getRoleName(userId, id, "TEAM");
                        switch (roleName.toUpperCase()) {
                            case "ADMIN" -> System.out.println("admin");
                            default -> System.out.println("other");
                        }
                    }
                }
                """);
            List<PropagateEntry> entries = scan(Arrays.asList(gate, caller));
            assertTrue(entries.isEmpty(),
                "switch(roleName.toUpperCase()) の roleName は switch という予約語への"
                    + "委譲ではなく検出されないべき: " + entries.size());
        }

        @Test
        @DisplayName("u2（除外しすぎ防止）: switchService への実在の委譲は除外されない")
        void u2_switchServiceへの委譲は除外されない() {
            Src caller = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoSwitchServiceResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoSwitchServiceResolver {
                    private final DemoAccessService accessControlService;
                    DemoSwitchServiceResolver(DemoAccessService s) { this.accessControlService = s; }
                    public Object resolve(Long userId, Long id) {
                        String roleName = accessControlService.getRoleName(userId, id, "TEAM");
                        return switchService.evaluate(id, userId, roleName);
                    }
                }
                """);
            List<PropagateEntry> entries = scan(Arrays.asList(gate, caller));
            assertTrue(entries.stream().anyMatch(
                e -> e.kind == Kind.DELEGATE_FORM && "evaluate".equals(e.calleeName)),
                "switch は除外対象だが、名前が似ているだけの switchService.evaluate(..) への"
                    + "実在の委譲は除外されず検出されるべき: " + entries.size());
        }

        // ── ノイズ除去 ②: ロガー呼び出しは認可の下流委譲ではない ──────────────────────

        @Test
        @DisplayName("v: log.warn(...) への引き渡しは委譲形 PROPAGATE として検出されない")
        void v_ロガー呼び出しは検出されない() {
            Src caller = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoLoggerResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoLoggerResolver {
                    private final DemoAccessService accessControlService;
                    DemoLoggerResolver(DemoAccessService s) { this.accessControlService = s; }
                    public void resolve(Long userId, Long id) {
                        String roleName = accessControlService.getRoleName(userId, id, "TEAM");
                        log.warn("RoleResolver: 未知のロール名 " + roleName);
                    }
                }
                """);
            List<PropagateEntry> entries = scan(Arrays.asList(gate, caller));
            assertTrue(entries.isEmpty(),
                "log.warn(...) はロール名の出力にすぎず、認可の下流委譲として"
                    + "検出されないべき: " + entries.size());
        }

        @Test
        @DisplayName("v2（除外しすぎ防止）: warningService.warn(...) への実在の委譲は除外されない")
        void v2_warningServiceへの委譲は除外されない() {
            Src caller = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoWarningServiceResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoWarningServiceResolver {
                    private final DemoAccessService accessControlService;
                    DemoWarningServiceResolver(DemoAccessService s) { this.accessControlService = s; }
                    public Object resolve(Long userId, Long id) {
                        String roleName = accessControlService.getRoleName(userId, id, "TEAM");
                        return warningService.warn(id, userId, roleName);
                    }
                }
                """);
            List<PropagateEntry> entries = scan(Arrays.asList(gate, caller));
            assertTrue(entries.stream().anyMatch(
                e -> e.kind == Kind.DELEGATE_FORM && "warn".equals(e.calleeName)),
                "log/logger 以外のレシーバ（warningService）への warn(..) 委譲は"
                    + "除外されず検出されるべき: " + entries.size());
        }
    }
}
