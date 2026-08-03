package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.security.SelfScopedEndpoint;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link SelfScopedEndpoint} マーカーの<b>付与要件</b>を機械的に強制する番人テスト
 * （認可漏れ(IDOR)全域監査戦役・凍結ストア全数返済の基盤）。
 *
 * <h2>この番人が守っているもの</h2>
 * <p>{@link SelfScopedEndpoint} は「検索・更新の対象が認証主体に束縛され、<b>他人のデータへ
 * 構造的に到達できない</b>」ことを監査を経て宣言するマーカーであり、
 * {@link AuthzControllerGuardArchTest} の認可シグナル (C) として認識される。
 * この宣言は<b>コード上の根拠を人間が読んで確認した結果</b>であって、注釈そのものが
 * 到達不能性を作り出すわけではない。したがって本注釈の価値は
 * 「<b>宣言に見合う検証（契約テスト）が実在するか</b>」に完全に依存する。</p>
 *
 * <p>そこで本番人は、{@link SelfScopedEndpoint} が付与された全エンドポイントについて
 * 次の 4 点を CI で機械的に要求する。<b>免除リストは設けない</b>
 * （{@code EXEMPTIONS} 相当の抜け道を置かないことが本番人の設計意図である）。</p>
 * <ol>
 *   <li><b>根拠の記述</b>: {@code value()} が空文字・空白のみでないこと。加えて
 *       {@value #MIN_REASON_LENGTH} 文字以上あること（「self」「OK」等の実質のない一言を弾く下限。
 *       根拠の妥当性そのものは人間のレビューで見る）。</li>
 *   <li><b>根拠がその場に読めること</b>: {@code value()} は<b>文字列リテラル</b>で書くこと
 *       （定数参照は、付与箇所を読んだだけでは根拠が追えないため不可）。</li>
 *   <li><b>Controller への付与</b>: 本注釈は公開エンドポイント（Controller の Mapping メソッド）
 *       専用である。Controller 以外のクラスに付与しても
 *       {@link AuthzControllerGuardArchTest} は参照しないため、死んだ証跡になる。</li>
 *   <li><b>契約テストの実在</b>: 当該エンドポイントの自己スコープ性を固定する
 *       契約テストが {@code src/test/java} 配下に存在すること（判定規則は下記）。</li>
 * </ol>
 *
 * <h2>契約テストの実在判定（トレーサビリティ・リンク方式）</h2>
 * <p>{@code src/test/java} 配下の Java ソースのうち、次をすべて満たすファイルが
 * 1 つ以上あれば「契約テストあり」とみなす。</p>
 * <ul>
 *   <li>当該エンドポイントの<b>宣言 Controller の単純名</b>を独立トークンとして含む</li>
 *   <li>当該エンドポイントの<b>メソッド名</b>を独立トークンとして含む</li>
 *   <li>JUnit のテストを実際に宣言している（{@code @Test} / {@code @ParameterizedTest} /
 *       {@code @RepeatedTest} / {@code @TestFactory} のいずれかを持つ）</li>
 *   <li>{@value #EXCLUDED_EVIDENCE_PATH_FRAGMENT} 配下でない（後述）</li>
 * </ul>
 *
 * <p><b>なぜ「名前を含むこと」で判定するのか</b>: 自己スコープ EP の契約テストは実運用上
 * MockMvc で URL を叩く形（{@code *ScopeContractIT}）が主流で、Controller のメソッドを
 * Java の呼び出しとして参照しない。つまり「呼び出し辺の存在」では判定できない。
 * よって本番人は<b>宣言的なトレーサビリティ・リンク</b>を要求する方式を採る。
 * 推奨する書き方は、契約テストの Javadoc / {@code @DisplayName} に
 * {@code <Controller 単純名>#<メソッド名>} を明記すること
 * （例: {@code 「PersonalTodoController#getMyTodos の自己スコープ性を固定する」}）。
 * これにより「どの EP の到達不能性を、どのテストが固定しているか」が
 * 検分時に機械的に追跡できる。</p>
 *
 * <h2>本番人が保証すること／保証しないこと（偽 green の限界を隠さない）</h2>
 * <p><b>保証する</b>: {@link SelfScopedEndpoint} を付与した全 EP について、根拠が読める形で
 * 書かれており、その EP を名指しした JUnit テストソースが実在すること。
 * すなわち「注釈を貼るだけで番人の出力を静かにする」ことはできない。</p>
 * <p><b>保証しない（既知の限界）</b>:</p>
 * <ul>
 *   <li>名指しは<b>コメント／文字列リテラル中でも成立する</b>（推奨形の Javadoc 明記を
 *       成立させるため、意図的にマスクしていない）。よって、名前だけ書いて中身が
 *       当該 EP を踏んでいないテストは本番人を通過しうる。<b>テストが実際に
 *       他ユーザーからの到達不能性を assert しているか</b>は人間の検分で見る前提である。</li>
 *   <li>その偽 green の主要な流入口である「名前の羅列だけを持つファイル」は 1 つだけ
 *       構造的に塞いでいる: {@value #EXCLUDED_EVIDENCE_PATH_FRAGMENT} 配下
 *       （本パッケージのアーキテクチャ番人・凍結ストア台帳）は証跡として数えない。
 *       これらは Javadoc に {@code Controller#method} を大量に列挙しつつ
 *       {@code @Test} も宣言しているため、除外しないと台帳の記述だけで
 *       要件が満たされてしまう。</li>
 *   <li>テストの「実行されること」は見ない（{@code @Disabled} / 条件付き無効化の検出はしない）。</li>
 * </ul>
 *
 * <h2>実装方式（ソース走査・{@code --tests} 絞り込みで自壊しない）</h2>
 * <p>{@link AuthzGateReturnValueGuardTest} / {@code ScopeSwitchExhaustivenessGuardTest} と
 * 同じ流儀で、{@code Files.walk} ＋ 軽量ソースパーサ ＋ {@code fail()} による違反列挙で実装する。
 * <b>本テストはファイルを読み取るだけで、いかなる書き込みも行わない</b>。
 * ArchUnit の {@code FreezingArchRule} を使わないため、
 * {@code ./gradlew test --tests "..."} の絞り込み実行で凍結ストアを破壊する事故
 * （{@link ArchUnitFreezeStoreIntegrityTest} が検知している事故）を自ら引き起こすことはない。</p>
 *
 * <p>走査対象は {@code src/main/java} のみである。{@code src/test/java} 配下の fixture
 * （{@code fixtures/SelfScopedMarkerAnnotatedController}）は本番人の対象外であり、
 * 契約テストを要求されない。</p>
 */
class SelfScopedEndpointMarkerGuardTest {

    /** 本番ソースの走査ルート（{@code backend/} を CWD とする Gradle テスト実行に合わせた相対パス）。 */
    private static final Path MAIN_SOURCE_ROOT = Paths.get("src", "main", "java");

    /** テストソースの走査ルート（契約テストの実在判定に使う）。 */
    private static final Path TEST_SOURCE_ROOT = Paths.get("src", "test", "java");

    /** マーカー注釈の単純名（ソース上のトークン）。 */
    private static final String MARKER_SIMPLE_NAME = SelfScopedEndpoint.class.getSimpleName();

    /** ソース上のマーカー注釈トークン。 */
    private static final String MARKER_TOKEN = "@" + MARKER_SIMPLE_NAME;

    /** {@code value()} に要求する最小文字数（実質のない一言を弾く下限）。 */
    private static final int MIN_REASON_LENGTH = 10;

    /**
     * 契約テストの証跡として数えないパス断片。
     *
     * <p>本パッケージ（アーキテクチャ番人・凍結ストア台帳）は Javadoc に
     * {@code Controller#method} を大量に列挙しつつ {@code @Test} も宣言しているため、
     * 除外しないと「台帳に名前が載っているだけ」で契約テスト要件が満たされてしまう。</p>
     */
    private static final String EXCLUDED_EVIDENCE_PATH_FRAGMENT =
        "com/mannschaft/app/common/architecture/";

    /** JUnit のテスト宣言を示す注釈トークン。 */
    private static final List<String> JUNIT_TEST_TOKENS =
        List.of("@Test", "@ParameterizedTest", "@RepeatedTest", "@TestFactory");

    // ═══════════════════════════════════════════════════════════════════════
    // 実ファイル走査テスト
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("@SelfScopedEndpoint の付与要件（根拠の記述・Controller への付与・契約テストの実在）を満たしていること")
    void 自己スコープマーカーの付与要件を満たしていること() throws IOException {
        List<Src> mainSources = loadSources(MAIN_SOURCE_ROOT);
        List<Src> testSources = loadSources(TEST_SOURCE_ROOT);

        List<Violation> violations = analyze(mainSources, testSources);
        if (violations.isEmpty()) {
            return;
        }
        fail(buildMessage(violations));
    }

    @Test
    @DisplayName("走査が空振りしていないこと（ソースを1件も読めていない状態での空虚 green 防止）")
    void 走査対象のソースを実際に読めていること() throws IOException {
        List<Src> mainSources = loadSources(MAIN_SOURCE_ROOT);
        List<Src> testSources = loadSources(TEST_SOURCE_ROOT);

        assertTrue(mainSources.size() > 500,
            "本番ソースの走査件数が少なすぎます（" + mainSources.size() + " 件）。"
                + "CWD またはソースルートの想定が崩れている可能性があります: "
                + MAIN_SOURCE_ROOT.toAbsolutePath());
        assertTrue(testSources.size() > 100,
            "テストソースの走査件数が少なすぎます（" + testSources.size() + " 件）。"
                + "契約テストの実在判定が常に「証跡なし」に倒れる恐れがあります: "
                + TEST_SOURCE_ROOT.toAbsolutePath());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 走査本体（実ファイル走査・fixture 自己検証で共通利用）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 本番ソースからマーカー付与箇所を抽出し、テストソースを証跡として突き合わせて違反を返す。
     *
     * <p>実ファイル走査と {@code @Nested} 自己検証が<b>同一コア</b>を通ることを保証するための
     * package-private エントリポイント（パーサ破損による空虚 green を防ぐ二重化）。</p>
     */
    static List<Violation> analyze(List<Src> mainSources, List<Src> testSources) {
        List<Target> targets = new ArrayList<>();
        for (Src s : mainSources) {
            targets.addAll(extractTargets(s));
        }
        if (targets.isEmpty()) {
            return List.of();
        }

        List<Src> evidence = new ArrayList<>();
        for (Src s : testSources) {
            if (isEligibleEvidence(s)) {
                evidence.add(s);
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (Target t : targets) {
            if (!t.controllerClass) {
                violations.add(new Violation(t, Kind.NOT_A_CONTROLLER, ""));
                // Controller でない付与は死んだ証跡なので、契約テストの有無は問わない。
                continue;
            }
            if (!t.reasonIsLiteral) {
                violations.add(new Violation(t, Kind.NON_LITERAL_REASON, ""));
            } else if (t.reason.isBlank()) {
                violations.add(new Violation(t, Kind.BLANK_REASON, ""));
            } else if (t.reason.strip().length() < MIN_REASON_LENGTH) {
                violations.add(new Violation(t, Kind.TOO_SHORT_REASON, t.reason.strip()));
            }
            if (findEvidence(t, evidence) == null) {
                violations.add(new Violation(t, Kind.NO_CONTRACT_TEST, ""));
            }
        }
        return violations;
    }

    /** 当該ターゲットの契約テスト証跡を 1 件返す（無ければ {@code null}）。 */
    static Src findEvidence(Target target, List<Src> evidence) {
        for (Src s : evidence) {
            // 意図的に「マスクしていない生ソース」で照合する。
            // 契約テストは Javadoc / @DisplayName で EP を名指しするのが推奨形であり、
            // コメント・文字列リテラルを潰すとその推奨形が成立しなくなる（限界は本クラス Javadoc に明記）。
            if (containsToken(s.content, target.controllerSimpleName)
                && containsToken(s.content, target.methodName)) {
                return s;
            }
        }
        return null;
    }

    /** テストソースが契約テストの証跡として適格か（JUnit テストを宣言し、除外パス配下でない）。 */
    static boolean isEligibleEvidence(Src testSource) {
        if (testSource.relPath.contains(EXCLUDED_EVIDENCE_PATH_FRAGMENT)) {
            return false;
        }
        String masked = mask(testSource.content);
        for (String token : JUNIT_TEST_TOKENS) {
            if (containsToken(masked, token)) {
                return true;
            }
        }
        return false;
    }

    // ── 本番ソースのパーサ ──────────────────────────────────────────────────

    /**
     * 1 本番ソースから {@link SelfScopedEndpoint} 付与箇所を抽出する。
     *
     * <p>注釈トークンの探索は<b>マスク済みソース</b>で行うため、Javadoc の
     * {@code {@link SelfScopedEndpoint}} 等の言及は拾わない（偽陽性回避）。
     * {@code value()} の内容は<b>生ソース</b>から読む（マスクで潰れているため）。</p>
     */
    static List<Target> extractTargets(Src src) {
        // 生ソースに名前が一切出てこないファイルはマスク処理自体を省く（全本番ソース走査の実費削減）。
        if (!src.content.contains(MARKER_SIMPLE_NAME)) {
            return List.of();
        }
        String masked = mask(src.content);
        if (!masked.contains(MARKER_TOKEN)) {
            return List.of();
        }
        String simpleName = simpleName(src.relPath);
        boolean controllerClass = isControllerSource(masked, simpleName);

        List<Target> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = masked.indexOf(MARKER_TOKEN, from);
            if (at < 0) {
                return out;
            }
            from = at + MARKER_TOKEN.length();
            // 「@SelfScopedEndpointFoo」のような別トークンを除外する。
            if (from < masked.length() && Character.isJavaIdentifierPart(masked.charAt(from))) {
                continue;
            }

            int cursor = skipWs(masked, from);
            String reason = "";
            boolean reasonIsLiteral = false;
            if (cursor < masked.length() && masked.charAt(cursor) == '(') {
                int close = matchParen(masked, cursor);
                if (close < 0) {
                    continue; // 括弧が閉じていない（コンパイル不能）ソースは対象外
                }
                String rawArg = src.content.substring(cursor + 1, close);
                String literal = concatStringLiterals(rawArg);
                if (literal != null) {
                    reason = literal;
                    reasonIsLiteral = true;
                }
                cursor = close + 1;
            }
            // value() は必須属性（default なし）なので括弧なしはコンパイルエラーになる。
            // それでも防御的に「リテラルでない＝根拠が読めない」として扱う。

            String methodName = resolveMethodName(masked, cursor);
            if (methodName == null) {
                continue;
            }
            out.add(new Target(src.relPath, simpleName, methodName, reason, reasonIsLiteral,
                controllerClass, lineOf(src.content, at)));
        }
    }

    /** ソースが Controller クラスか（{@code @RestController}/{@code @Controller} 付与、または命名）。 */
    static boolean isControllerSource(String masked, String simpleName) {
        return containsToken(masked, "@RestController")
            || containsToken(masked, "@Controller")
            || simpleName.endsWith("Controller");
    }

    /**
     * マスク済みソースの {@code fromIndex} 以降から、後続する注釈・修飾子・戻り型を読み飛ばして
     * メソッド名を解決する。
     *
     * <p>{@code @SelfScopedEndpoint("...")} の後に {@code @GetMapping("/me")} 等の注釈が
     * 続く形（実運用で最も多い）を正しく扱うため、注釈とその引数リストを繰り返し読み飛ばし、
     * その後に現れる最初の {@code (}（＝引数リスト）の直前の識別子をメソッド名とみなす。</p>
     */
    static String resolveMethodName(String masked, int fromIndex) {
        int cursor = fromIndex;
        while (true) {
            cursor = skipWs(masked, cursor);
            if (cursor >= masked.length() || masked.charAt(cursor) != '@') {
                break;
            }
            // 注釈名（ドット区切りの限定名も許す）を読み飛ばす。
            int p = cursor + 1;
            while (p < masked.length()
                && (Character.isJavaIdentifierPart(masked.charAt(p)) || masked.charAt(p) == '.')) {
                p++;
            }
            int afterName = skipWs(masked, p);
            if (afterName < masked.length() && masked.charAt(afterName) == '(') {
                int close = matchParen(masked, afterName);
                if (close < 0) {
                    return null;
                }
                cursor = close + 1;
            } else {
                cursor = p;
            }
        }
        int paren = masked.indexOf('(', cursor);
        if (paren < 0) {
            return null;
        }
        int end = skipWsBack(masked, paren - 1);
        if (end < 0 || !Character.isJavaIdentifierPart(masked.charAt(end))) {
            return null;
        }
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(masked.charAt(start - 1))) {
            start--;
        }
        return masked.substring(start, end + 1);
    }

    /**
     * 注釈引数（生ソース）から文字列リテラルの連結結果を返す。リテラルが 1 つも無ければ
     * {@code null}（定数参照等＝付与箇所を読んでも根拠が追えない形）。
     *
     * <p>{@code "a" + "b"} の連結とテキストブロック {@code """..."""} を扱う。
     * エスケープは<b>空白系（{@code \t} {@code \n} {@code \r} {@code \s} 等）を復元する</b>
     * ところまで解釈する。これが無いと {@code "\t"}（実質は空白のみ）が
     * 「{@code t} という 1 文字の根拠」と誤読され、空白のみの根拠を見逃す。</p>
     */
    static String concatStringLiterals(String rawArg) {
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        int i = 0;
        int n = rawArg.length();
        while (i < n) {
            char c = rawArg.charAt(i);
            if (c == '"' && i + 2 < n && rawArg.charAt(i + 1) == '"' && rawArg.charAt(i + 2) == '"') {
                int end = rawArg.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    break;
                }
                sb.append(rawArg, i + 3, end);
                found = true;
                i = end + 3;
                continue;
            }
            if (c == '"') {
                int p = i + 1;
                while (p < n && rawArg.charAt(p) != '"') {
                    if (rawArg.charAt(p) == '\\' && p + 1 < n) {
                        sb.append(unescape(rawArg.charAt(p + 1)));
                        p += 2;
                        continue;
                    }
                    sb.append(rawArg.charAt(p));
                    p++;
                }
                found = true;
                i = Math.min(n, p + 1);
                continue;
            }
            i++;
        }
        return found ? sb.toString() : null;
    }

    /** 単純なエスケープ 1 文字を復元する（空白系を空白として扱えるようにするのが主目的）。 */
    private static char unescape(char escaped) {
        switch (escaped) {
            case 't':
                return '\t';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 'f':
                return '\f';
            case 'b':
                return '\b';
            case 's':
                return ' ';
            default:
                // \" \\ \' 等はその文字自身。\\uXXXX 等は根拠文の空白判定に影響しないため素通し。
                return escaped;
        }
    }

    // ── ファイル読み込み ────────────────────────────────────────────────────

    private static List<Src> loadSources(Path root) throws IOException {
        assertTrue(Files.isDirectory(root),
            "ソースルートが見つかりません: " + root.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");
        List<Src> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .forEach(p -> {
                    String content;
                    try {
                        content = Files.readString(p, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    out.add(new Src(p.toString().replace('\\', '/'), content));
                });
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 失敗メッセージ
    // ═══════════════════════════════════════════════════════════════════════

    private static String buildMessage(List<Violation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("@").append(MARKER_SIMPLE_NAME)
            .append(" の付与要件を満たしていない箇所があります（")
            .append(violations.size()).append(" 件）。\n")
            .append("本マーカーは「他人のデータへ構造的に到達できない」という監査結果の宣言であり、"
                + "宣言に見合う検証（契約テスト）の実在を本番人が機械的に要求します。\n\n");
        for (Violation v : violations) {
            Target t = v.target;
            sb.append("  ✗ ").append(t.relPath).append(':').append(t.line)
                .append("  ").append(t.controllerSimpleName).append('#').append(t.methodName)
                .append('\n')
                .append("      理由: ").append(v.kind.why).append('\n')
                .append("      対処: ").append(v.kind.remedyFor(t)).append('\n');
            if (!v.detail.isEmpty()) {
                sb.append("      現状: ").append(v.detail).append('\n');
            }
        }
        sb.append("\n本マーカーに免除リストは設けていません。要件を満たせない場合は、"
            + "マーカーを外して従来どおり凍結ストアに残す（＝未返済として可視化し続ける）か、"
            + "実効的な認可（AccessControlService / *AccessGuard 等）を敷設してください。\n")
            .append("運用ルール: backend/.claudecode.md の ArchUnit 番人節 / docs/security/README.md §4.2");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 軽量パーサ・ユーティリティ（AuthzGateReturnValueGuardTest と同型）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * コメント・文字列/文字リテラルの内側を空白へ潰した文字列を返す。
     * 長さ・改行・区切り文字は保持し、原文とオフセットが 1:1 で対応する。
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
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && a[i] != quote) {
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
            i++;
        }
        return new String(out);
    }

    /** {@code token} が識別子境界で独立して出現するか。 */
    static boolean containsToken(String haystack, String token) {
        int from = 0;
        while (true) {
            int i = haystack.indexOf(token, from);
            if (i < 0) {
                return false;
            }
            boolean okBefore = i == 0 || !Character.isJavaIdentifierPart(haystack.charAt(i - 1));
            int end = i + token.length();
            boolean okAfter = end >= haystack.length()
                || !Character.isJavaIdentifierPart(haystack.charAt(end));
            if (okBefore && okAfter) {
                return true;
            }
            from = i + 1;
        }
    }

    private static String simpleName(String relPath) {
        String base = relPath.substring(relPath.lastIndexOf('/') + 1);
        return base.endsWith(".java") ? base.substring(0, base.length() - ".java".length()) : base;
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

    // ═══════════════════════════════════════════════════════════════════════
    // パーサ・判定ロジックの自己検証（正例・負例）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * インライン fixture で本番人の判定挙動を固定する自己検証。
     *
     * <p>実ファイル走査テストは、マーカーが 1 件も付与されていない状態では常に緑になる
     * （本マーカー新設 PR 時点がまさにその状態）。パーサが壊れても「違反 0 件＝緑」のまま
     * 通ってしまい、番人が静かに空虚化しうる。本自己検証は<b>負例で「違反が返ること」を
     * assert</b> するため、パーサが壊れればここが赤くなる。
     * 実ファイル走査と<b>同一コア</b>（{@link #analyze(List, List)}）を通す。</p>
     */
    @Nested
    @DisplayName("パーサ・判定ロジックの自己検証（正例・負例）")
    class 判定ロジック自己検証 {

        private static final String CONTROLLER_PATH =
            "src/main/java/com/mannschaft/app/demo/controller/DemoSelfController.java";

        /** マーカー付与済み Controller の fixture（{@code reason} と本体を差し替えられる）。 */
        private Src controller(String annotationArg) {
            String body = """
                package com.mannschaft.app.demo.controller;

                import com.mannschaft.app.common.security.SelfScopedEndpoint;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class DemoSelfController {

                    @SelfScopedEndpoint(__ARG__)
                    @GetMapping("/api/v1/demo/me")
                    public java.util.List<String> getMyItems() {
                        return demoService.findMine();
                    }
                }
                """;
            return new Src(CONTROLLER_PATH, body.replace("__ARG__", annotationArg));
        }

        private Src contractTest(String relPath, String extra) {
            String body = """
                package com.mannschaft.app.demo;

                import org.junit.jupiter.api.Test;

                /** DemoSelfController#getMyItems の自己スコープ性を固定する契約テスト。 */
                class DemoSelfScopeContractIT {
                    @Test
                    void 他人のデータへ到達できないこと() {
                __EXTRA__
                    }
                }
                """;
            return new Src(relPath, body.replace("__EXTRA__", extra));
        }

        private List<Violation> analyzeWith(Src main, List<Src> tests) {
            return analyze(List.of(main), tests);
        }

        private boolean hasKind(List<Violation> violations, Kind kind) {
            return violations.stream().anyMatch(v -> v.kind == kind);
        }

        // ── 正例 ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("正例: 根拠あり＋Controller＋名指しの契約テストあり → 違反 0 件")
        void 正例_要件を満たす付与は違反にならない() {
            List<Violation> violations = analyzeWith(
                controller("\"リポジトリクエリが認証主体の userId に束縛される（DemoService#findMine）\""),
                List.of(contractTest(
                    "src/test/java/com/mannschaft/app/demo/DemoSelfScopeContractIT.java", "")));
            assertTrue(violations.isEmpty(),
                "要件を満たす付与は違反にならないべき: " + violations);
        }

        @Test
        @DisplayName("正例: 文字列連結で書いた根拠も読み取れる → 違反 0 件")
        void 正例_連結リテラルの根拠も読み取れる() {
            List<Violation> violations = analyzeWith(
                controller("\"リポジトリクエリが認証主体の userId に束縛される\"\n            + \"（DemoService#findMine）\""),
                List.of(contractTest(
                    "src/test/java/com/mannschaft/app/demo/DemoSelfScopeContractIT.java", "")));
            assertTrue(violations.isEmpty(),
                "連結リテラルの根拠も読み取れるべき: " + violations);
        }

        // ── 負例 ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("負例: value() が空文字 → BLANK_REASON")
        void 負例_空文字の根拠は違反() {
            List<Violation> violations = analyzeWith(
                controller("\"\""),
                List.of(contractTest(
                    "src/test/java/com/mannschaft/app/demo/DemoSelfScopeContractIT.java", "")));
            assertTrue(hasKind(violations, Kind.BLANK_REASON),
                "空文字の根拠は違反であるべき: " + violations);
        }

        @Test
        @DisplayName("負例: value() が空白のみ → BLANK_REASON")
        void 負例_空白のみの根拠は違反() {
            List<Violation> violations = analyzeWith(
                controller("\"   \\t \""),
                List.of(contractTest(
                    "src/test/java/com/mannschaft/app/demo/DemoSelfScopeContractIT.java", "")));
            assertTrue(hasKind(violations, Kind.BLANK_REASON),
                "空白のみの根拠は違反であるべき: " + violations);
        }

        @Test
        @DisplayName("負例: value() が実質のない一言 → TOO_SHORT_REASON")
        void 負例_短すぎる根拠は違反() {
            List<Violation> violations = analyzeWith(
                controller("\"self\""),
                List.of(contractTest(
                    "src/test/java/com/mannschaft/app/demo/DemoSelfScopeContractIT.java", "")));
            assertTrue(hasKind(violations, Kind.TOO_SHORT_REASON),
                "実質のない一言の根拠は違反であるべき: " + violations);
        }

        @Test
        @DisplayName("負例: value() が定数参照（付与箇所で根拠が読めない） → NON_LITERAL_REASON")
        void 負例_定数参照の根拠は違反() {
            List<Violation> violations = analyzeWith(
                controller("SelfScopeReasons.BOUND_TO_CURRENT_USER"),
                List.of(contractTest(
                    "src/test/java/com/mannschaft/app/demo/DemoSelfScopeContractIT.java", "")));
            assertTrue(hasKind(violations, Kind.NON_LITERAL_REASON),
                "定数参照の根拠は違反であるべき: " + violations);
        }

        @Test
        @DisplayName("負例: 契約テストが存在しない → NO_CONTRACT_TEST")
        void 負例_契約テストが無ければ違反() {
            List<Violation> violations = analyzeWith(
                controller("\"リポジトリクエリが認証主体の userId に束縛される\""),
                List.of());
            assertTrue(hasKind(violations, Kind.NO_CONTRACT_TEST),
                "契約テストが無い付与は違反であるべき: " + violations);
        }

        @Test
        @DisplayName("負例: メソッド名は載っているが Controller 名が無いテスト → NO_CONTRACT_TEST")
        void 負例_Controller名の無いテストは証跡にならない() {
            Src test = new Src("src/test/java/com/mannschaft/app/demo/DemoServiceTest.java",
                """
                package com.mannschaft.app.demo;
                import org.junit.jupiter.api.Test;
                class DemoServiceTest {
                    @Test
                    void getMyItems_のみ言及() { }
                }
                """);
            List<Violation> violations = analyzeWith(
                controller("\"リポジトリクエリが認証主体の userId に束縛される\""), List.of(test));
            assertTrue(hasKind(violations, Kind.NO_CONTRACT_TEST),
                "Controller 名を名指ししないテストは証跡にならないべき: " + violations);
        }

        @Test
        @DisplayName("負例: 名前は載っているが JUnit テストを宣言していないファイル → NO_CONTRACT_TEST")
        void 負例_テストを宣言しないファイルは証跡にならない() {
            Src helper = new Src("src/test/java/com/mannschaft/app/demo/DemoFixtures.java",
                """
                package com.mannschaft.app.demo;
                /** DemoSelfController#getMyItems 用のヘルパ（テストではない）。 */
                class DemoFixtures {
                    static String note() { return "getMyItems"; }
                }
                """);
            List<Violation> violations = analyzeWith(
                controller("\"リポジトリクエリが認証主体の userId に束縛される\""), List.of(helper));
            assertTrue(hasKind(violations, Kind.NO_CONTRACT_TEST),
                "JUnit テストを宣言しないファイルは証跡にならないべき: " + violations);
        }

        @Test
        @DisplayName("負例: アーキテクチャ番人・凍結ストア台帳の記述は証跡にならない → NO_CONTRACT_TEST")
        void 負例_番人台帳の記述は証跡にならない() {
            Src ledger = new Src(
                "src/test/java/com/mannschaft/app/common/architecture/SomeLedgerTest.java",
                """
                package com.mannschaft.app.common.architecture;
                import org.junit.jupiter.api.Test;
                /** DemoSelfController#getMyItems は自己スコープのため凍結のまま残す。 */
                class SomeLedgerTest {
                    @Test
                    void 行数が期待値と一致する() { }
                }
                """);
            List<Violation> violations = analyzeWith(
                controller("\"リポジトリクエリが認証主体の userId に束縛される\""), List.of(ledger));
            assertTrue(hasKind(violations, Kind.NO_CONTRACT_TEST),
                "番人・台帳の Javadoc 記述は契約テストの証跡にならないべき: " + violations);
        }

        @Test
        @DisplayName("負例: Controller 以外のクラスへの付与 → NOT_A_CONTROLLER")
        void 負例_Controller以外への付与は違反() {
            Src service = new Src(
                "src/main/java/com/mannschaft/app/demo/service/DemoService.java",
                """
                package com.mannschaft.app.demo.service;
                import com.mannschaft.app.common.security.SelfScopedEndpoint;
                public class DemoService {
                    @SelfScopedEndpoint("リポジトリクエリが認証主体の userId に束縛される")
                    public java.util.List<String> findMine() { return java.util.List.of(); }
                }
                """);
            List<Violation> violations = analyze(List.of(service), List.of());
            assertTrue(hasKind(violations, Kind.NOT_A_CONTROLLER),
                "Controller 以外への付与は違反であるべき: " + violations);
        }

        @Test
        @DisplayName("負例: 部分一致は証跡にならない（getMyItemsExtra は getMyItems の証跡でない）")
        void 負例_部分一致は証跡にならない() {
            Src test = contractTest(
                "src/test/java/com/mannschaft/app/demo/DemoOtherContractIT.java", "")
                .withContent(c -> c.replace("getMyItems", "getMyItemsExtra"));
            List<Violation> violations = analyzeWith(
                controller("\"リポジトリクエリが認証主体の userId に束縛される\""), List.of(test));
            assertTrue(hasKind(violations, Kind.NO_CONTRACT_TEST),
                "識別子の部分一致は証跡にならないべき（トークン境界判定）: " + violations);
        }

        // ── 偽陽性回避 ────────────────────────────────────────────────────

        @Test
        @DisplayName("偽陽性回避: Javadoc の {@link SelfScopedEndpoint} 言及は付与箇所として拾わない")
        void 偽陽性回避_Javadoc言及は付与箇所ではない() {
            Src doc = new Src(
                "src/main/java/com/mannschaft/app/demo/controller/DocOnlyController.java",
                """
                package com.mannschaft.app.demo.controller;
                import org.springframework.web.bind.annotation.RestController;
                /**
                 * 自己スコープ EP には @SelfScopedEndpoint を付ける方針である（説明のみ）。
                 * 文字列でも "@SelfScopedEndpoint" と書くことがある。
                 */
                @RestController
                public class DocOnlyController {
                    public String plain() { return "x"; }
                }
                """);
            List<Violation> violations = analyze(List.of(doc), List.of());
            assertTrue(violations.isEmpty(),
                "コメント・文字列中の言及は付与箇所として拾わないべき: " + violations);
        }

        // ── パーサ裏取り（空虚 green 防止） ────────────────────────────────

        @Test
        @DisplayName("裏取り: パーサが付与箇所（メソッド名・根拠・Controller 判定）を実際に抽出できている")
        void 裏取り_付与箇所の抽出() {
            List<Target> targets = extractTargets(
                controller("\"リポジトリクエリが認証主体の userId に束縛される\""));
            assertEquals(1, targets.size(), "付与箇所を 1 件抽出できるべき: " + targets);
            Target t = targets.get(0);
            assertEquals("DemoSelfController", t.controllerSimpleName);
            assertEquals("getMyItems", t.methodName,
                "後続の @GetMapping を読み飛ばしてメソッド名を解決できるべき");
            assertTrue(t.reasonIsLiteral, "根拠はリテラルとして読み取れるべき");
            assertTrue(t.reason.contains("userId"), "根拠の本文を読み取れるべき: " + t.reason);
            assertTrue(t.controllerClass, "@RestController 付きは Controller と判定されるべき");
        }

        @Test
        @DisplayName("裏取り: 証跡適格判定（JUnit テスト宣言あり／番人パッケージは不適格）")
        void 裏取り_証跡適格判定() {
            assertTrue(isEligibleEvidence(contractTest(
                    "src/test/java/com/mannschaft/app/demo/DemoSelfScopeContractIT.java", "")),
                "@Test を宣言する通常のテストは証跡として適格であるべき");
            assertFalse(isEligibleEvidence(contractTest(
                    "src/test/java/com/mannschaft/app/common/architecture/FooTest.java", "")),
                "アーキテクチャ番人パッケージは証跡として不適格であるべき");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部保持型
    // ═══════════════════════════════════════════════════════════════════════

    /** 走査対象ソース（相対パス＋内容）。自己検証から参照できるよう package-private。 */
    static final class Src {
        final String relPath;
        final String content;

        Src(String relPath, String content) {
            this.relPath = relPath;
            this.content = content;
        }

        /** 内容だけを変換した複製を返す（自己検証の fixture 組み立て用）。 */
        Src withContent(java.util.function.UnaryOperator<String> transform) {
            return new Src(relPath, transform.apply(content));
        }
    }

    /** マーカー付与箇所 1 件。 */
    static final class Target {
        final String relPath;
        final String controllerSimpleName;
        final String methodName;
        final String reason;
        final boolean reasonIsLiteral;
        final boolean controllerClass;
        final int line;

        Target(String relPath, String controllerSimpleName, String methodName, String reason,
               boolean reasonIsLiteral, boolean controllerClass, int line) {
            this.relPath = relPath;
            this.controllerSimpleName = controllerSimpleName;
            this.methodName = methodName;
            this.reason = reason;
            this.reasonIsLiteral = reasonIsLiteral;
            this.controllerClass = controllerClass;
            this.line = line;
        }

        @Override
        public String toString() {
            return controllerSimpleName + "#" + methodName + " (" + relPath + ":" + line + ")";
        }
    }

    /** 違反の種別（理由と対処をセットで持つ）。 */
    enum Kind {
        BLANK_REASON(
            "value() に自己スコープである根拠が書かれていない（空文字・空白のみ）。"
                + "根拠なき付与は、後年の監査で到達不能性を再確認する手がかりを失わせる。",
            "検索・更新の対象がどのように認証主体へ束縛されているかを、"
                + "追跡可能な形（クラス名・メソッド名）で value() に記述してください。"),
        TOO_SHORT_REASON(
            "value() の根拠が短すぎる（" + MIN_REASON_LENGTH + " 文字未満）。"
                + "一言では、どのクエリのどの条件が認証主体に束縛されているのか追跡できない。",
            "「どのクラスのどのメソッドが、認証主体の何を検索条件に使っているか」まで"
                + "書き下してください。"),
        NON_LITERAL_REASON(
            "value() が文字列リテラルで書かれていない（定数参照等）。"
                + "付与箇所を読んだだけでは根拠が追えず、監査の証跡として機能しない。",
            "根拠をその場に読める文字列リテラルで直接記述してください。"),
        NOT_A_CONTROLLER(
            "Controller 以外のクラスに付与されている。本マーカーは公開エンドポイント"
                + "（Controller の Mapping メソッド）専用であり、"
                + "他の場所に付けても認可番人は参照しない＝死んだ証跡になる。",
            "Controller の Mapping メソッドへ移すか、付与を撤去してください。"),
        NO_CONTRACT_TEST(
            "自己スコープ性を固定する契約テストが見つからない。"
                + "本マーカーは「監査で到達不能性を確認した」という宣言であり、"
                + "その確認を回帰させ続ける契約テストが無ければ宣言が朽ちる。",
            "");

        private final String why;
        private final String remedy;

        Kind(String why, String remedy) {
            this.why = why;
            this.remedy = remedy;
        }

        /** 対処文（{@link #NO_CONTRACT_TEST} のみ対象の名前を埋め込む）。 */
        String remedyFor(Target target) {
            if (this != NO_CONTRACT_TEST) {
                return remedy;
            }
            return "当該エンドポイントを踏み、無関係な他ユーザーからは対象データへ到達できないことを"
                + "固定する契約テスト（*ScopeContractIT 等）を追加し、その Javadoc または @DisplayName に "
                + "「" + target.controllerSimpleName + "#" + target.methodName + "」"
                + "を明記してください（既存の契約テストに追記でも可）。"
                + "アーキテクチャ番人パッケージへの記述は証跡になりません。";
        }
    }

    /** 違反 1 件。 */
    static final class Violation {
        final Target target;
        final Kind kind;
        final String detail;

        Violation(Target target, Kind kind, String detail) {
            this.target = target;
            this.kind = kind;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return kind + " @ " + target;
        }
    }
}
