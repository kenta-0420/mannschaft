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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「見せかけゲート」検出番人（認可根治・裏目付戦役 部隊B）。
 *
 * <h2>背景 — Wave4 番人（{@code AuthzControllerGuardArchTest}）の盲点</h2>
 * <p>既存の認可番人はバイトコードで「認可クラスへの<b>呼び出し辺が在るか</b>」を見る。
 * だが「その戻り値が<b>拒否に繋がっているか</b>」までは見ない。よって次のような
 * <b>見せかけゲート</b>を素通ししてしまう:</p>
 * <pre>{@code
 *   // 呼んではいるが戻り値を捨てている。canAccess が false でも処理が続行する。
 *   guard.canAccess(id);
 *   doSensitiveThing(id);
 * }</pre>
 * <p>これはバイトコード上「呼び出し辺あり」と観測され Wave4 番人を通過するが、実際には
 * 認可が<b>まったく効いていない</b>。本番人はこの「boolean 返却ゲートの戻り値破棄」を
 * ソース走査で機械検出する。</p>
 *
 * <h2>なぜ ArchUnit バイトコードでなくソース走査か</h2>
 * <p>「戻り値が {@code if}/{@code throw}/代入/条件式のどこに消費されるか」は、コンパイル後の
 * バイトコード（{@code invokevirtual} の直後に {@code pop} が来るか {@code ifeq} が来るか等）
 * からは辿りにくく、最適化・インライン・スタック操作で容易に化ける。
 * よって {@link ScopeSwitchExhaustivenessGuardTest}（#2443）と同じ流儀 ——
 * {@code Files.walk} ＋ 軽量ソースパーサ ＋ {@code fail()} で違反列挙 —— で作る。</p>
 *
 * <h2>検出対象（誤検出を出さないための厳密な限定）</h2>
 * <ol>
 *   <li><b>ゲートクラス</b>: クラス名（＝ファイル名）が {@code *AccessGuard} /
 *       {@code *AccessService} / {@code *AuthorizationService} で終わる、または
 *       {@code AccessControlService} である認可判定クラス。</li>
 *   <li><b>ゲートメソッド</b>: 上記クラスが宣言する <b>{@code boolean} 返却</b>で、
 *       名前が {@code can*} / {@code is*} / {@code has*} / {@code allows*} / {@code may*}
 *       で始まるメソッド（許可可否を bool で返す判定）。メソッド名の集合は実ソースから動的に収集する。</li>
 *   <li><b>違反</b>: 上記ゲートメソッドの呼び出しが、<b>結果を捨てる単独の式文</b>
 *       （{@code recv.canX(..);} が {@code ;} 終端の独立文）になっている箇所。</li>
 * </ol>
 * <p><b>{@code check*}/{@code require*}/{@code assert*} など void 返却で内部 {@code throw}
 * する様式は対象外</b>（それが正しいゲート様式であり、戻り値の消費という概念が無い）。
 * boolean 返却ゲートの「消費漏れ」だけを狙う。</p>
 *
 * <h2>消費されている（合格）／捨てている（違反）の判定</h2>
 * <p>呼び出し {@code recv.canX(..)} の戻り値は、次のいずれかであれば<b>消費されている</b>とみなし合格とする:</p>
 * <ul>
 *   <li>{@code if (recv.canX(..))} — 条件（レシーバの直前が {@code (}）</li>
 *   <li>{@code return recv.canX(..)} — 戻り値（直前トークンが {@code return}）</li>
 *   <li>{@code boolean b = recv.canX(..)} — 代入（直前が {@code =}）</li>
 *   <li>{@code !recv.canX(..)} — 否定（直前が {@code !}）</li>
 *   <li>{@code recv.canX(..) && ..} / {@code || ..} / 三項 {@code ? :} — 論理・条件式の一部
 *       （閉じ {@code )} の直後が {@code ;} でない）</li>
 *   <li>{@code foo(recv.canX(..))} — メソッド引数（直前が {@code (}）</li>
 *   <li>{@code recv.canX(..).and(..)} — メソッド連鎖（閉じ {@code )} の直後が {@code .}）</li>
 * </ul>
 * <p><b>違反</b>は「閉じ {@code )} の直後が {@code ;}」かつ「レシーバ式の直前が文境界
 * （{@code ;} {@code &#123;} {@code &#125;}、{@code else}/{@code do}、case ラベルの {@code :}、
 * {@code if}/{@code for}/{@code while} の波括弧無し単文）」の場合のみ。
 * 消費に該当する形はすべて除外する。</p>
 *
 * <h2>誤検出（false positive）を抑える工夫</h2>
 * <ul>
 *   <li><b>レシーバ型解決</b>: ゲートメソッド名（{@code isAdmin} 等）は他ドメインの
 *       オブジェクトにも存在しうる。よって呼び出しレシーバ識別子が、その<b>同一ファイル内で
 *       ゲートクラス型として宣言された変数・フィールド・引数</b>である場合のみ対象にする。
 *       {@code someEntity.isAdmin();}（entity は非ゲート型）は巻き込まない。</li>
 *   <li>コメント・文字列リテラルは {@link #mask(String)} で潰し、疑似トークンを無視する。</li>
 *   <li>{@code this.guard.canX();} の {@code this.} 前置も剥がして文境界を正しく判定する。</li>
 * </ul>
 * <p><b>既知の限定（安全側に倒す）</b>: レシーバがローカル宣言でない複雑な連鎖
 * （{@code a.b().guard.canX()} 等）・レシーバ無しの自クラス自己呼び出し
 * （{@code canX();}、void 過負荷と紛らわしい）は対象外とする（recall より precision を優先）。</p>
 *
 * <h2>免除リスト</h2>
 * <p>正当な理由で対象外にするものは {@link #EXEMPTIONS} に理由コメント付きで静的登録できる。
 * 発足時点では <b>0 件（クリーン発足）</b>。凍結ストアへの書き戻しは行わないため、
 * {@code --tests} 絞り込み実行で ArchUnit 凍結ストアを破壊する事故は起こさない。</p>
 */
class AuthzGateReturnValueGuardTest {

    /** 走査ルート（{@code backend/} を CWD とする Gradle テスト実行に合わせた相対パス）。 */
    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /** boolean 返却ゲートメソッドの名前接頭辞。 */
    private static final Set<String> GATE_METHOD_PREFIXES =
        Set.of("can", "is", "has", "allows", "may");

    /**
     * 免除リスト（{@code "<相対パス>:<行番号>"} 形式）。
     * 正当な理由で本番人の対象から外すものを理由コメント付きで登録する。発足時点では空。
     */
    private static final Set<String> EXEMPTIONS = Set.of(
        // 例: "src/main/java/.../FooController.java:123"  // 理由: 〇〇のため（副作用取得で意図的に破棄 等）
    );

    private static final Set<String> KEYWORDS = Set.of(
        "extends", "implements", "throws", "return", "new", "instanceof",
        "public", "private", "protected", "static", "final", "abstract");

    // ═══════════════════════════════════════════════════════════════════════
    // 実ファイル走査テスト
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("認可ゲート(boolean返却)の戻り値を捨てる単独式文（見せかけゲート）が存在しないこと")
    void 認可ゲートの戻り値を破棄する見せかけゲートが無いこと() throws IOException {
        List<Src> sources = loadAllSources();
        List<Violation> violations = analyzeSources(sources);
        if (violations.isEmpty()) {
            return;
        }
        fail(buildMessage(
            "認可ゲート（boolean を返す can*/is*/has*/allows*/may* メソッド）の呼び出しが、"
                + "戻り値を捨てる単独の式文になっています。これは呼び出し辺は存在するため Wave4 番人"
                + "（AuthzControllerGuardArchTest）を通過しますが、戻り値を if/throw/代入 のいずれにも"
                + "繋いでいないため、ゲートが false を返しても処理が続行する“見せかけゲート”です。",
            "戻り値を if 分岐で拒否に繋ぐ（例: if (!guard.canX(id)) throw new AccessDeniedException();）、"
                + "あるいは throw 様式の check*/require* ゲートに置き換えてください。",
            violations));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 走査本体（実ファイル走査・fixture 自己検証で共通利用）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * ソース集合を 2 パスで解析する。
     * <p>パス1: ゲートクラスを特定し boolean ゲートメソッド名を収集。
     * パス2: 全ソースからゲートメソッドの戻り値破棄呼び出しを検出。</p>
     *
     * <p>実ファイル走査と {@code @Nested} 自己検証が <b>同一コア</b>を通ることを保証するための
     * package-private エントリポイント（パーサ破損による空虚 green を防ぐ二重化）。</p>
     */
    static List<Violation> analyzeSources(List<Src> sources) {
        // ── パス1: ゲートクラス名 & boolean ゲートメソッド名を収集 ──
        Set<String> gateClassNames = new HashSet<>();
        Set<String> gateMethodNames = new HashSet<>();
        for (Src s : sources) {
            if (isGateClassFile(s.relPath)) {
                gateClassNames.add(simpleName(s.relPath));
                gateMethodNames.addAll(collectBooleanGateMethods(mask(s.content)));
            }
        }
        // ── パス2: 戻り値破棄呼び出しを検出 ──
        List<Violation> violations = new ArrayList<>();
        if (gateMethodNames.isEmpty() || gateClassNames.isEmpty()) {
            return violations;
        }
        for (Src s : sources) {
            violations.addAll(findDiscardedGateCalls(s.relPath, s.content, gateMethodNames, gateClassNames));
        }
        return violations;
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
                    String src;
                    try {
                        src = Files.readString(p, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    String rel = SOURCE_ROOT.resolve(SOURCE_ROOT.relativize(p)).toString()
                        .replace('\\', '/');
                    out.add(new Src(rel, src));
                });
        }
        return out;
    }

    // ── パス1 ヘルパ ────────────────────────────────────────────────────────

    /** ファイル（＝クラス）名がゲートクラス命名規約に合致するか。 */
    static boolean isGateClassFile(String relPath) {
        String name = simpleName(relPath);
        return name.endsWith("AccessGuard")
            || name.endsWith("AccessService")
            || name.endsWith("AuthorizationService")
            || name.equals("AccessControlService");
    }

    private static String simpleName(String relPath) {
        String base = relPath.substring(relPath.lastIndexOf('/') + 1);
        return base.endsWith(".java") ? base.substring(0, base.length() - ".java".length()) : base;
    }

    private static final Pattern BOOLEAN_METHOD =
        Pattern.compile("\\bboolean\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");

    /** マスク済みゲートクラスソースから boolean 返却ゲートメソッド名を収集する。 */
    static Set<String> collectBooleanGateMethods(String masked) {
        Set<String> names = new TreeSet<>();
        Matcher m = BOOLEAN_METHOD.matcher(masked);
        while (m.find()) {
            String name = m.group(1);
            if (hasGatePrefix(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static boolean hasGatePrefix(String name) {
        for (String p : GATE_METHOD_PREFIXES) {
            if (name.length() > p.length() && name.startsWith(p)
                && Character.isUpperCase(name.charAt(p.length()))) {
                return true;
            }
        }
        return false;
    }

    // ── パス2 ヘルパ ────────────────────────────────────────────────────────

    /**
     * 1 ファイル内で、ゲートメソッドの戻り値を捨てる単独式文を検出する。
     */
    static List<Violation> findDiscardedGateCalls(String relPath, String src,
                                                  Set<String> gateMethods, Set<String> gateClassNames) {
        String masked = mask(src);
        Set<String> receivers = resolveGateReceivers(masked, gateClassNames);
        List<Violation> out = new ArrayList<>();
        if (receivers.isEmpty()) {
            return out;
        }
        int n = masked.length();
        int i = 0;
        while (i < n) {
            // メソッド名候補（識別子）を走査
            char c = masked.charAt(i);
            if (!Character.isJavaIdentifierStart(c)) {
                i++;
                continue;
            }
            int idStart = i;
            int j = i + 1;
            while (j < n && Character.isJavaIdentifierPart(masked.charAt(j))) {
                j++;
            }
            String ident = masked.substring(idStart, j);
            // 「.<method>(」の形か
            int dot = skipWsBack(masked, idStart - 1);
            int afterId = skipWs(masked, j);
            boolean isCall = afterId < n && masked.charAt(afterId) == '(';
            boolean dotBefore = dot >= 0 && masked.charAt(dot) == '.'
                && !(dot >= 1 && masked.charAt(dot - 1) == '.'); // 「::」やスプレッドは無いが「..」除外
            if (isCall && dotBefore && gateMethods.contains(ident)) {
                Violation v = checkCall(relPath, src, masked, idStart, dot, afterId, receivers);
                if (v != null) {
                    out.add(v);
                }
            }
            i = j;
        }
        return out;
    }

    /**
     * 呼び出し {@code recv.method(...)} が戻り値破棄の単独式文かを判定し、違反なら {@link Violation} を返す。
     *
     * @param methodStart メソッド名識別子の開始位置
     * @param dot         メソッド名直前の {@code .} の位置
     * @param paren       引数開き {@code (} の位置
     */
    private static Violation checkCall(String relPath, String src, String masked,
                                       int methodStart, int dot, int paren, Set<String> receivers) {
        // レシーバ識別子（. の直前）を取得
        int recvEnd = skipWsBack(masked, dot - 1);
        if (recvEnd < 0 || !Character.isJavaIdentifierPart(masked.charAt(recvEnd))) {
            return null;
        }
        int recvStart = recvEnd;
        while (recvStart > 0 && Character.isJavaIdentifierPart(masked.charAt(recvStart - 1))) {
            recvStart--;
        }
        String recv = masked.substring(recvStart, recvEnd + 1);
        if (!receivers.contains(recv)) {
            return null; // ゲート型でないレシーバは対象外（FP 回避）
        }

        // 引数の閉じ ) を求め、その直後が ; でなければ「消費されている」
        int close = matchParen(masked, paren);
        if (close < 0) {
            return null;
        }
        int after = skipWs(masked, close + 1);
        if (after >= masked.length() || masked.charAt(after) != ';') {
            return null; // 連鎖 / 論理式 / 三項の一部など → 消費されている
        }

        // レシーバ式の先頭（this. や単純フィールドアクセス前置を剥がす）
        int exprStart = leadingReferenceStart(masked, recvStart);

        if (!precededByStatementBoundary(masked, exprStart)) {
            return null; // return/=/(/!/&&/? などに消費されている
        }

        int line = lineOf(src, exprStart);
        if (EXEMPTIONS.contains(relPath + ":" + line)) {
            return null;
        }
        String snippet = snippet(src, exprStart, after);
        return new Violation(relPath, line, snippet);
    }

    /**
     * レシーバ識別子の開始位置から、前置の {@code this.} や {@code field.} 参照連鎖を遡って
     * 「参照式全体の先頭」を返す。複雑な前置（メソッド呼び出し {@code foo().x} 等）は遡らない。
     */
    private static int leadingReferenceStart(String masked, int recvStart) {
        int cur = recvStart;
        while (true) {
            int p = skipWsBack(masked, cur - 1);
            if (p < 0 || masked.charAt(p) != '.') {
                return cur;
            }
            // 「.」の前が識別子（this / フィールド名）なら遡る。
            int q = skipWsBack(masked, p - 1);
            if (q < 0 || !Character.isJavaIdentifierPart(masked.charAt(q))) {
                return cur; // ).x のような複雑前置は遡らない
            }
            int segStart = q;
            while (segStart > 0 && Character.isJavaIdentifierPart(masked.charAt(segStart - 1))) {
                segStart--;
            }
            cur = segStart;
        }
    }

    /**
     * 参照式先頭 {@code exprStart} の直前が「文の境界」か（＝戻り値を消費するものが前に無いか）を判定する。
     */
    private static boolean precededByStatementBoundary(String masked, int exprStart) {
        int p = skipWsBack(masked, exprStart - 1);
        if (p < 0) {
            return true; // 領域先頭
        }
        char c = masked.charAt(p);
        if (c == ';' || c == '{' || c == '}') {
            return true;
        }
        if (c == ':') {
            return isLabelColon(masked, p); // case/label のコロンなら文境界。三項の : は消費
        }
        if (c == ')') {
            return isBracelessControlFlowHead(masked, p); // if/for/while の波括弧無し単文なら文境界
        }
        if (Character.isJavaIdentifierPart(c)) {
            // 直前トークンを読む
            int wStart = p;
            while (wStart > 0 && Character.isJavaIdentifierPart(masked.charAt(wStart - 1))) {
                wStart--;
            }
            String w = masked.substring(wStart, p + 1);
            // else/do は単文を導く＝文境界。return/new など値を消費する語は非境界。
            return w.equals("else") || w.equals("do") || w.equals("try") || w.equals("finally");
        }
        // '=', '(', '!', '&', '|', '?', ',', '.', 演算子, '>'(-> の一部) など → 消費されている
        return false;
    }

    /** コロン {@code :} が case/label ラベルのものか（三項の else でないか）を判定する。 */
    private static boolean isLabelColon(String masked, int colon) {
        // 直前の文境界（; { }）まで遡り、その区間に「?」が無ければラベル（三項でない）。
        for (int k = colon - 1; k >= 0; k--) {
            char c = masked.charAt(k);
            if (c == ';' || c == '{' || c == '}') {
                break;
            }
            if (c == '?') {
                return false; // 三項の else コロン
            }
        }
        return true;
    }

    /** {@code )} が if/for/while の波括弧無し単文ヘッダの閉じ括弧かを判定する。 */
    private static boolean isBracelessControlFlowHead(String masked, int closeParen) {
        int open = matchOpenParen(masked, closeParen);
        if (open < 0) {
            return false;
        }
        int p = skipWsBack(masked, open - 1);
        if (p < 0 || !Character.isJavaIdentifierPart(masked.charAt(p))) {
            return false;
        }
        int wStart = p;
        while (wStart > 0 && Character.isJavaIdentifierPart(masked.charAt(wStart - 1))) {
            wStart--;
        }
        String w = masked.substring(wStart, p + 1);
        return w.equals("if") || w.equals("for") || w.equals("while");
    }

    /**
     * 同一ファイル内でゲートクラス型として宣言された変数・フィールド・引数の識別子名を収集する。
     */
    static Set<String> resolveGateReceivers(String masked, Set<String> gateClassNames) {
        Set<String> receivers = new HashSet<>();
        for (String cls : gateClassNames) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(cls) + "\\s+([a-z_$][A-Za-z0-9_$]*)");
            Matcher m = p.matcher(masked);
            while (m.find()) {
                int s = m.start();
                // 前が識別子の一部 / '.'（限定名）なら別トークン
                if (s > 0 && (Character.isJavaIdentifierPart(masked.charAt(s - 1))
                    || masked.charAt(s - 1) == '.')) {
                    continue;
                }
                String id = m.group(1);
                if (!KEYWORDS.contains(id)) {
                    receivers.add(id);
                }
            }
        }
        return receivers;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 軽量パーサ・ユーティリティ（ScopeSwitchExhaustivenessGuardTest と同型）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * コメント・文字列/文字リテラルの内側を空白へ潰した文字列を返す。
     * 長さ・改行・区切り文字（{@code "} {@code '}）は保持し、原文とオフセットが 1:1 で対応する。
     */
    static String mask(String s) {
        char[] a = s.toCharArray();
        char[] out = a.clone();
        int n = a.length;
        int i = 0;
        while (i < n) {
            char c = a[i];
            if (c == '/' && i + 1 < n && a[i + 1] == '/') {
                while (i < n && a[i] != '\n') {
                    out[i] = ' ';
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && a[i + 1] == '*') {
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                while (i < n && !(a[i] == '*' && i + 1 < n && a[i + 1] == '/')) {
                    if (a[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i] = ' ';
                    if (i + 1 < n) {
                        out[i + 1] = ' ';
                    }
                    i += 2;
                }
                continue;
            }
            if (c == '"' && i + 2 < n && a[i + 1] == '"' && a[i + 2] == '"') {
                i += 3;
                while (i < n && !(a[i] == '"' && i + 1 < n && a[i + 1] == '"'
                        && i + 2 < n && a[i + 2] == '"')) {
                    if (a[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                i = Math.min(n, i + 3);
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n && a[i] != '"') {
                    if (a[i] == '\\' && i + 1 < n) {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i += 2;
                        continue;
                    }
                    if (a[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    i++;
                }
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n && a[i] != '\'') {
                    if (a[i] == '\\' && i + 1 < n) {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i += 2;
                        continue;
                    }
                    out[i] = ' ';
                    i++;
                }
                if (i < n) {
                    i++;
                }
                continue;
            }
            i++;
        }
        return new String(out);
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipWsBack(String s, int i) {
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int matchParen(String s, int open) {
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

    private static int matchOpenParen(String s, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
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

    private static String snippet(String src, int from, int semicolon) {
        int to = Math.min(src.length(), semicolon + 1);
        String raw = src.substring(Math.max(0, from), to);
        return raw.replaceAll("\\s+", " ").trim();
    }

    private static String buildMessage(String why, String remedy, List<Violation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append(why).append('\n').append(remedy).append("\n違反箇所 (")
            .append(violations.size()).append(" 件):\n");
        for (Violation v : violations) {
            sb.append("  ✗ ").append(v.relPath).append(':').append(v.line)
                .append("  ").append(v.callExpr).append('\n');
        }
        sb.append("（正当な例外は ")
            .append(AuthzGateReturnValueGuardTest.class.getSimpleName())
            .append(".EXEMPTIONS に理由付きで登録できます）");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // パーサ自己検証（fixture で「失敗すべき時に失敗する」ことを固定する）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * インライン fixture でパーサの検出挙動を固定する自己検証。
     *
     * <p>実ファイル走査テストは、実ソースに違反が無ければ常に緑になる。パーサ（{@link #mask}・
     * レシーバ型解決・文境界判定）が改修で壊れても「違反 0 件＝緑」のまま通り、番人が静かに
     * 空虚化（vacuous）しうる。本自己検証は <b>陽性 fixture で「違反が返ること」を assert</b>
     * するため、パーサが壊れればここが赤くなり空虚化を検知できる（#2443 と同じ思想）。
     * 実ファイル走査と <b>同一コア</b>（{@link #analyzeSources(List)}）を通す。</p>
     */
    @Nested
    @DisplayName("パーサ自己検証（fixture）")
    class パーサ自己検証 {

        /** ゲートクラス fixture（boolean ゲートメソッド canAccess/isAdmin を宣言）。 */
        private final Src gate = new Src(
            "src/main/java/com/mannschaft/app/demo/service/DemoAccessGuard.java",
            """
            package com.mannschaft.app.demo.service;
            public class DemoAccessGuard {
                public boolean canAccess(Long id) { return id != null; }
                public boolean isAdmin(Long id) { return false; }
                public void requireAccess(Long id) { if (!canAccess(id)) throw new RuntimeException(); }
            }
            """);

        private List<Violation> analyze(String callerBody) {
            String caller = """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessGuard;
                public class DemoController {
                    private final DemoAccessGuard guard;
                    DemoController(DemoAccessGuard guard) { this.guard = guard; }
                    void handle(Long id) {
                __BODY__
                    }
                }
                """.replace("__BODY__", callerBody);
            return analyzeSources(Arrays.asList(gate,
                new Src("src/main/java/com/mannschaft/app/demo/web/DemoController.java", caller)));
        }

        private boolean hasViolation(String body) {
            return !analyze(body).isEmpty();
        }

        // ── 陽性（違反として検出されること） ──────────────────────────────

        @Test
        @DisplayName("a: 戻り値を捨てる単独式文 guard.canAccess(id); → 違反")
        void a_戻り値破棄_単独文() {
            assertTrue(hasViolation("        guard.canAccess(id);"),
                "戻り値を捨てる単独式文は違反であるべき（見せかけゲート）");
        }

        @Test
        @DisplayName("b: this. 前置でも捨てていれば違反 this.guard.isAdmin(id); → 違反")
        void b_this前置_破棄() {
            assertTrue(hasViolation("        this.guard.isAdmin(id);"),
                "this. 前置の戻り値破棄も違反であるべき");
        }

        @Test
        @DisplayName("c: case ラベル直後の破棄 case 1: guard.canAccess(id); → 違反")
        void c_caseラベル直後_破棄() {
            String body = """
                        switch (id.intValue()) {
                            case 1: guard.canAccess(id); break;
                            default: break;
                        }
                """;
            assertTrue(hasViolation(body), "case ラベル直後の戻り値破棄も違反であるべき");
        }

        @Test
        @DisplayName("d: else 単文の破棄 else guard.canAccess(id); → 違反")
        void d_else単文_破棄() {
            String body = """
                        if (id == null) return;
                        else guard.canAccess(id);
                """;
            assertTrue(hasViolation(body), "else 単文の戻り値破棄も違反であるべき");
        }

        // ── 陰性（消費されている＝false positive を出さないこと） ────────────

        @Test
        @DisplayName("e: if 条件は消費 if (!guard.canAccess(id)) throw; → 非違反")
        void e_if条件_消費() {
            assertFalse(hasViolation("        if (!guard.canAccess(id)) { throw new RuntimeException(); }"),
                "if 条件での消費は非違反であるべき");
        }

        @Test
        @DisplayName("f: return は消費 return guard.canAccess(id); → 非違反")
        void f_return_消費() {
            assertFalse(hasViolation("        return guard.canAccess(id);"),
                "return での消費は非違反であるべき");
        }

        @Test
        @DisplayName("g: 代入は消費 boolean ok = guard.canAccess(id); → 非違反")
        void g_代入_消費() {
            assertFalse(hasViolation("        boolean ok = guard.canAccess(id); if (!ok) throw new RuntimeException();"),
                "代入での消費は非違反であるべき");
        }

        @Test
        @DisplayName("h: 論理積・メソッド引数・三項での消費 → 非違反")
        void h_式の一部_消費() {
            assertFalse(hasViolation("        boolean r = guard.canAccess(id) && guard.isAdmin(id);"),
                "&& の一部は消費であるべき");
            assertFalse(hasViolation("        java.util.Objects.requireNonNull(guard.canAccess(id));"),
                "メソッド引数は消費であるべき");
            assertFalse(hasViolation("        int x = guard.canAccess(id) ? 1 : 0;"),
                "三項の条件は消費であるべき");
        }

        @Test
        @DisplayName("i: 非ゲート型レシーバの同名メソッドは対象外 entity.isAdmin(); → 非違反")
        void i_非ゲート型レシーバ_対象外() {
            String body = """
                        java.util.List<Long> entity = null;
                        entity.isEmpty();
                """;
            // isEmpty は boolean だがゲートクラス由来でない → メソッド名集合に無い
            assertFalse(hasViolation(body), "非ゲート由来メソッドは対象外であるべき");

            // ゲートメソッド名 isAdmin と同名でも、レシーバがゲート型でなければ対象外
            String sameName = """
                        SomeEntity entity = new SomeEntity();
                        entity.isAdmin();
                """;
            assertFalse(hasViolation(sameName),
                "同名でも非ゲート型レシーバなら対象外であるべき（FP 回避）");
        }

        @Test
        @DisplayName("j: メソッド連鎖は消費 guard.canAccess(id).toString(); → 非違反")
        void j_連鎖_消費() {
            // canAccess の戻りに更にメソッドを呼ぶ形は「戻り値をレシーバとして消費」＝非違反
            assertFalse(hasViolation("        String s = String.valueOf(guard.canAccess(id));"),
                "連鎖・引数消費は非違反であるべき");
        }

        @Test
        @DisplayName("k: コメント/文字列内の疑似呼び出しはマスクで無視 → 非違反")
        void k_マスク() {
            String body = """
                        // guard.canAccess(id);  ← コメントなので無視
                        String note = "guard.canAccess(id);";
                        boolean ok = guard.canAccess(id);
                        if (!ok) throw new RuntimeException();
                """;
            assertFalse(hasViolation(body), "コメント/文字列内の疑似呼び出しは検出されないべき");
        }

        @Test
        @DisplayName("l: check*/require*(void) 様式は戻り値概念が無く対象外 → 非違反")
        void l_require様式_対象外() {
            assertFalse(hasViolation("        guard.requireAccess(id);"),
                "require* は boolean ゲートでないので対象外であるべき");
        }

        @Test
        @DisplayName("m: パス1がゲートメソッド名を実際に収集できていること（空集合による空虚 green 防止）")
        void m_ゲートメソッド収集の裏取り() {
            Set<String> methods = collectBooleanGateMethods(mask(gate.content));
            assertTrue(methods.contains("canAccess") && methods.contains("isAdmin"),
                "boolean ゲートメソッド canAccess/isAdmin を収集できるべき: " + methods);
            assertFalse(methods.contains("requireAccess"), "void の require* は収集しないべき");
            assertTrue(isGateClassFile(gate.relPath), "DemoAccessGuard はゲートクラスと判定されるべき");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部保持型
    // ═══════════════════════════════════════════════════════════════════════

    /** 走査対象ソース（相対パス＋内容）。fixture 自己検証から参照できるよう package-private。 */
    static final class Src {
        final String relPath;
        final String content;

        Src(String relPath, String content) {
            this.relPath = relPath;
            this.content = content;
        }
    }

    /** 違反 1 件。 */
    static final class Violation {
        final String relPath;
        final int line;
        final String callExpr;

        Violation(String relPath, int line, String callExpr) {
            this.relPath = relPath;
            this.line = line;
            this.callExpr = callExpr;
        }
    }
}
