package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.batch.BatchEndpointExempt;
import com.mannschaft.app.common.batch.PodLocalScheduled;
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

import static com.mannschaft.app.common.architecture.SelfScopedEndpointMarkerGuardTest.concatStringLiterals;
import static com.mannschaft.app.common.architecture.SelfScopedEndpointMarkerGuardTest.containsToken;
import static com.mannschaft.app.common.architecture.SelfScopedEndpointMarkerGuardTest.mask;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * バッチ規約の例外マーカー（{@link PodLocalScheduled} / {@link BatchEndpointExempt}）の
 * <b>付与要件</b>を機械的に強制する二次番人（issue #2601 Phase 1-d）。
 *
 * <h2>なぜ二次番人が要るのか</h2>
 * <p>一次番人 {@link ScheduledBatchGuardTest} は「{@code @Scheduled} には {@code @SchedulerLock} と
 * {@code @BatchEndpoint} を併記せよ」を強制する。その例外を表現するのが上記 2 マーカーだが、
 * <b>マーカーは番人の出力を黙らせる力を持つ</b>。理由の無い付与を許すと、
 * 「ロックの付け忘れ」と「Pod ローカルが設計意図」の区別がコード上から消え、
 * マーカーは<b>実装漏れを永久に凍結するバックドア</b>になる
 * （{@code IntentionallyPublic} / {@code SelfScopedEndpoint} と同じ規約思想）。</p>
 *
 * <p>そこで本番人は、マーカーの全付与箇所に次の 4 点を CI で機械的に要求する。
 * <b>免除リストは設けない</b>（抜け道を置かないことが本番人の設計意図である）。</p>
 * <ol>
 *   <li><b>理由がその場に読めること</b>: {@code value()} を<b>文字列リテラル</b>で書くこと
 *       （定数参照は、付与箇所を読んだだけでは根拠が追えない）。</li>
 *   <li><b>理由が実質を伴うこと</b>: 空文字・空白のみでなく、{@value #MIN_REASON_LENGTH} 文字以上あること
 *       （「不要」「pod local」等の一言を弾く下限。理由の妥当性そのものは人間の検分で見る）。</li>
 *   <li><b>Javadoc が併記されていること</b>: 付与対象メソッドに Javadoc があること。
 *       {@code value()} は一行の要約に過ぎず、「多重実行時に何が起きるか」「代替の可観測性を
 *       どこで担保しているか」は Javadoc に残さなければ後年の監査で追えない。</li>
 *   <li><b>{@code @Scheduled} メソッドへの付与であること</b>: 本マーカーは一次番人の判定に対して
 *       のみ意味を持つ。他の場所に付けても一次番人は参照しないため、死んだ証跡になる。</li>
 * </ol>
 *
 * <h2>なぜ ArchUnit（バイトコード）ではなくソース走査なのか</h2>
 * <p>要件 3（Javadoc の併記）は<b>バイトコードに残らない</b>ため、ArchUnit では原理的に検証できない。
 * よって {@link SelfScopedEndpointMarkerGuardTest} / {@code ScopeSwitchExhaustivenessGuardTest} と
 * 同じ流儀で、{@code Files.walk} ＋ 軽量ソースパーサ ＋ {@code fail()} による違反列挙で実装する。
 * 文字列・コメントのマスク処理や文字列リテラル連結の解釈は
 * {@link SelfScopedEndpointMarkerGuardTest} の実績あるユーティリティを<b>再利用</b>する
 * （同一パッケージ・同一責務のパーサを二重実装しないため）。</p>
 *
 * <p><b>本テストはファイルを読み取るだけで、いかなる書き込みも行わない。</b>
 * ArchUnit の {@code FreezingArchRule} を使わないため、{@code ./gradlew test --tests "..."} の
 * 絞り込み実行で凍結ストアを破壊する事故（{@link ArchUnitFreezeStoreIntegrityTest} が検知している事故）を
 * 自ら引き起こすことはない。</p>
 *
 * <h2>空虚 green の防止</h2>
 * <p>マーカーの付与件数が少ない（発足時点で 5 箇所）ため、パーサが壊れて 0 件抽出になっても
 * 実ファイル走査は緑のままになりうる。よって {@link 判定ロジック自己検証} が
 * インライン fixture の<b>負例で「違反が返ること」を assert</b> し、
 * 実ファイル走査と<b>同一コア</b>（{@link #analyze(List)}）を通す。</p>
 */
class BatchMarkerAnnotationGuardTest {

    /** 本番ソースの走査ルート（{@code backend/} を CWD とする Gradle テスト実行に合わせた相対パス）。 */
    private static final Path MAIN_SOURCE_ROOT = Paths.get("src", "main", "java");

    /** 検証対象のマーカー注釈（単純名）。 */
    private static final List<String> MARKER_SIMPLE_NAMES = List.of(
        PodLocalScheduled.class.getSimpleName(),
        BatchEndpointExempt.class.getSimpleName());

    /** {@code value()} に要求する最小文字数（実質のない一言を弾く下限）。 */
    static final int MIN_REASON_LENGTH = 20;

    // ═══════════════════════════════════════════════════════════════════════
    // 実ファイル走査テスト
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("バッチ例外マーカーの付与要件（理由リテラル・Javadoc 併記・@Scheduled への付与）を満たしていること")
    void バッチ例外マーカーの付与要件を満たしていること() throws IOException {
        List<Violation> violations = analyze(loadSources(MAIN_SOURCE_ROOT));
        if (violations.isEmpty()) {
            return;
        }
        fail(buildMessage(violations));
    }

    @Test
    @DisplayName("走査が空振りしていないこと（ソースを1件も読めていない状態での空虚 green 防止）")
    void 走査対象のソースを実際に読めていること() throws IOException {
        List<Source> mainSources = loadSources(MAIN_SOURCE_ROOT);

        assertTrue(mainSources.size() > 500,
            "本番ソースの走査件数が少なすぎます（" + mainSources.size() + " 件）。"
                + "CWD またはソースルートの想定が崩れている可能性があります: "
                + MAIN_SOURCE_ROOT.toAbsolutePath());
    }

    @Test
    @DisplayName("裏取り: 実コードのマーカー付与箇所を実際に抽出できている（パーサ破損の検知）")
    void 実コードのマーカー付与箇所を抽出できている() throws IOException {
        List<Target> targets = new ArrayList<>();
        for (Source s : loadSources(MAIN_SOURCE_ROOT)) {
            targets.addAll(extractTargets(s));
        }

        assertTrue(targets.size() >= 5,
            "マーカーの付与箇所を 1 件も抽出できていない、または想定より少ない（"
                + targets.size() + " 件）。パーサが壊れると『違反 0 件』で静かに緑になるため、"
                + "抽出件数そのものを固定する。付与を意図的に減らした場合は本アサーションも更新すること。"
                + " 抽出結果: " + targets);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 走査本体（実ファイル走査・自己検証で共通利用する単一コア）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 本番ソースからマーカー付与箇所を抽出し、付与要件違反を返す。
     *
     * @param mainSources 走査対象の本番ソース
     * @return 違反一覧（空なら合格）
     */
    static List<Violation> analyze(List<Source> mainSources) {
        List<Violation> violations = new ArrayList<>();
        for (Source s : mainSources) {
            for (Target t : extractTargets(s)) {
                if (!t.reasonIsLiteral) {
                    violations.add(new Violation(t, Kind.NON_LITERAL_REASON, ""));
                } else if (t.reason.isBlank()) {
                    violations.add(new Violation(t, Kind.BLANK_REASON, ""));
                } else if (t.reason.strip().length() < MIN_REASON_LENGTH) {
                    violations.add(new Violation(t, Kind.TOO_SHORT_REASON, t.reason.strip()));
                }
                if (!t.hasJavadoc) {
                    violations.add(new Violation(t, Kind.NO_JAVADOC, ""));
                }
                if (!t.onScheduledMethod) {
                    violations.add(new Violation(t, Kind.NOT_ON_SCHEDULED_METHOD, ""));
                }
            }
        }
        return violations;
    }

    // ── パーサ ──────────────────────────────────────────────────────────

    /** 1 ソースからマーカー付与箇所をすべて抽出する。 */
    static List<Target> extractTargets(Source src) {
        List<Target> out = new ArrayList<>();
        for (String markerName : MARKER_SIMPLE_NAMES) {
            // 生ソースに名前が一切出てこないファイルはマスク処理自体を省く（全本番ソース走査の実費削減）。
            if (!src.content.contains(markerName)) {
                continue;
            }
            String masked = mask(src.content);
            String token = "@" + markerName;
            if (!masked.contains(token)) {
                continue;
            }
            int from = 0;
            while (true) {
                int at = masked.indexOf(token, from);
                if (at < 0) {
                    break;
                }
                from = at + token.length();
                // 「@PodLocalScheduledFoo」のような別トークンを除外する。
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
                    String literal = concatStringLiterals(src.content.substring(cursor + 1, close));
                    if (literal != null) {
                        reason = literal;
                        reasonIsLiteral = true;
                    }
                    cursor = close + 1;
                }
                // value() は必須属性（default なし）なので括弧なしはコンパイルエラーになる。
                // それでも防御的に「リテラルでない＝根拠が読めない」として扱う。

                MethodRef ref = resolveMethod(masked, cursor);
                if (ref == null) {
                    continue;
                }
                int blockStart = annotationBlockStart(masked, at);
                boolean hasJavadoc = javadocEndsJustBefore(masked, src.content, blockStart);
                String annotationBlock = masked.substring(blockStart, ref.parenIndex);
                boolean onScheduled = containsToken(annotationBlock, "@Scheduled")
                    || containsToken(annotationBlock, "@Schedules");

                out.add(new Target(src.relPath, markerName, ref.name, reason, reasonIsLiteral,
                    hasJavadoc, onScheduled, lineOf(src.content, at)));
            }
        }
        return out;
    }

    /**
     * マーカー出現位置から遡り、そのメソッドに付いた<b>注釈ブロックの先頭</b>のオフセットを返す。
     *
     * <p>マーカーより前に {@code @Scheduled} が書かれている形（順序は書き手の自由）でも
     * 注釈ブロック全体を見られるようにするため、連続する注釈行を遡る。
     * 行単位で「注釈行または注釈の継続行」とみなせるあいだ遡り、
     * Javadoc の終端・文の終端・ブロックの終端・空行に達したら止まる。</p>
     */
    static int annotationBlockStart(String masked, int markerOffset) {
        int lineStart = lineStartOf(masked, markerOffset);
        while (lineStart > 0) {
            int prevEnd = lineStart - 1;                    // 直前行の改行位置
            // 改行位置そのものから遡ることで、直前行が空行の場合に prevStart == prevEnd となり
            // 「空行に達した＝注釈ブロックの外」を正しく検出できる（1 行余計に遡ると、
            // 別メソッドの @Scheduled を拾って NOT_ON_SCHEDULED_METHOD を見逃す）。
            int prevStart = lineStartOf(masked, prevEnd);
            String prev = masked.substring(prevStart, Math.max(prevStart, prevEnd)).strip();
            if (prev.isEmpty() || prev.endsWith(";") || prev.endsWith("{") || prev.endsWith("}")) {
                break;
            }
            // Javadoc / ブロックコメントはマスクで空白化されているため、上の空行判定で止まる。
            lineStart = prevStart;
            if (prevStart == 0) {
                break;
            }
        }
        return lineStart;
    }

    /**
     * 注釈ブロックの直前が Javadoc（{@code /** ... *​/}）で終わっているか。
     *
     * <p>マスク済みソースではコメントが空白化されているため、<b>生ソース</b>を見て判定する。
     * 注釈ブロック直前の非空白文字が {@code /} かつその手前が {@code *} であれば
     * ブロックコメントの終端であり、さらにその開始が {@code /**} なら Javadoc とみなす。</p>
     */
    static boolean javadocEndsJustBefore(String masked, String raw, int blockStart) {
        int i = blockStart - 1;
        while (i >= 0 && Character.isWhitespace(raw.charAt(i))) {
            i--;
        }
        if (i < 1 || raw.charAt(i) != '/' || raw.charAt(i - 1) != '*') {
            return false;
        }
        int open = raw.lastIndexOf("/*", i - 1);
        if (open < 0) {
            return false;
        }
        // 「/**」で始まるものだけを Javadoc とみなす（「/* 実装コメント */」は不可）。
        return raw.startsWith("/**", open)
            // 実質のない「/***/」を弾く（本文が 1 文字も無い Javadoc は根拠にならない）。
            && !raw.substring(open + 3, i - 1).isBlank()
            // masked 側でも同区間がコメントとして空白化されていること（文字列リテラル中の誤検知防止）。
            && masked.substring(open, i + 1).isBlank();
    }

    /** マーカー注釈の直後から、後続の注釈・修飾子・戻り型を読み飛ばしてメソッドを解決する。 */
    static MethodRef resolveMethod(String masked, int fromIndex) {
        int cursor = fromIndex;
        while (true) {
            cursor = skipWs(masked, cursor);
            if (cursor >= masked.length() || masked.charAt(cursor) != '@') {
                break;
            }
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
        return new MethodRef(masked.substring(start, end + 1), paren);
    }

    // ── ファイル読み込み ────────────────────────────────────────────────────

    private static List<Source> loadSources(Path root) throws IOException {
        assertTrue(Files.isDirectory(root),
            "ソースルートが見つかりません: " + root.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");
        List<Source> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        out.add(new Source(p.toString().replace('\\', '/'),
                            Files.readString(p, StandardCharsets.UTF_8)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 失敗メッセージ
    // ═══════════════════════════════════════════════════════════════════════

    private static String buildMessage(List<Violation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("バッチ例外マーカーの付与要件を満たしていない箇所があります（")
            .append(violations.size()).append(" 件）。\n")
            .append("これらのマーカーはバッチ規約番人（ScheduledBatchGuardTest）の出力を黙らせる力を持つため、"
                + "理由なき付与は『実装漏れの永久凍結』と区別がつかなくなります。\n\n");
        for (Violation v : violations) {
            Target t = v.target;
            sb.append("  ✗ ").append(t.relPath).append(':').append(t.line)
                .append("  @").append(t.markerSimpleName).append(" on ").append(t.methodName)
                .append('\n')
                .append("      理由: ").append(v.kind.why).append('\n')
                .append("      対処: ").append(v.kind.remedy).append('\n');
            if (!v.detail.isEmpty()) {
                sb.append("      現状: ").append(v.detail).append('\n');
            }
        }
        sb.append("\n本マーカーに免除リストは設けていません。要件を満たせない場合は、"
            + "マーカーを外して @SchedulerLock / @BatchEndpoint を正しく併記してください。\n")
            .append("運用ルール: backend/.claudecode.md のバッチ節 / TEST_CONVENTION.md");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 小道具
    // ═══════════════════════════════════════════════════════════════════════

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

    private static int lineStartOf(String s, int offset) {
        int i = Math.min(offset, s.length() - 1);
        while (i > 0 && s.charAt(i - 1) != '\n') {
            i--;
        }
        return Math.max(i, 0);
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
     * <p>実ファイル走査テストは、パーサが壊れて 0 件抽出になっても「違反 0 件＝緑」のまま通る。
     * 本自己検証は<b>負例で「違反が返ること」を assert</b> するため、パーサが壊れればここが赤くなる。
     * 実ファイル走査と<b>同一コア</b>（{@link #analyze(List)}）を通す。</p>
     */
    @Nested
    @DisplayName("パーサ・判定ロジックの自己検証（正例・負例）")
    class 判定ロジック自己検証 {

        private static final String BATCH_PATH =
            "src/main/java/com/mannschaft/app/demo/batch/DemoFlushBatch.java";

        /** マーカー付与済みバッチの fixture（Javadoc・注釈引数・後続注釈を差し替えられる）。 */
        private Source batch(String javadoc, String annotationArg, String trailingAnnotations) {
            String body = """
                package com.mannschaft.app.demo.batch;

                import com.mannschaft.app.common.batch.PodLocalScheduled;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class DemoFlushBatch {

                    private final DemoService demoService = null;

                __JAVADOC__
                    @PodLocalScheduled(__ARG__)
                __TRAILING__
                    public void flush() {
                        demoService.flush();
                    }
                }
                """;
            return new Source(BATCH_PATH, body
                .replace("__JAVADOC__", javadoc)
                .replace("__ARG__", annotationArg)
                .replace("__TRAILING__", trailingAnnotations));
        }

        private static final String JAVADOC = """
                    /**
                     * Pod ローカルのバッファを flush する。
                     */""";

        private static final String SCHEDULED = "    @Scheduled(fixedDelay = 300_000)";

        private static final String GOOD_REASON =
            "\"Pod ローカルのメモリバッファを flush するため、ロックを掛けると敗者 Pod のバッファが永久に残る\"";

        private boolean hasKind(List<Violation> violations, Kind kind) {
            return violations.stream().anyMatch(v -> v.kind == kind);
        }

        // ── 正例 ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("正例: 理由リテラル＋Javadoc＋@Scheduled への付与 → 違反 0 件")
        void 正例_要件を満たす付与は違反にならない() {
            List<Violation> violations =
                analyze(List.of(batch(JAVADOC, GOOD_REASON, SCHEDULED)));
            assertTrue(violations.isEmpty(), "要件を満たす付与は違反にならないべき: " + violations);
        }

        @Test
        @DisplayName("正例: @Scheduled がマーカーより前に書かれていても認識する（注釈の順序は自由）")
        void 正例_注釈の順序が逆でも認識する() {
            Source src = new Source(BATCH_PATH, """
                package com.mannschaft.app.demo.batch;

                public class DemoFlushBatch {

                    /**
                     * Pod ローカルのバッファを flush する。
                     */
                    @Scheduled(fixedDelay = 300_000)
                    @PodLocalScheduled("Pod ローカルのメモリバッファを flush するため、ロックを掛けると敗者 Pod のバッファが永久に残る")
                    public void flush() {
                    }
                }
                """);
            assertTrue(analyze(List.of(src)).isEmpty(),
                "注釈の記述順に依存して要件判定が変わってはならない: " + analyze(List.of(src)));
        }

        @Test
        @DisplayName("正例: @Schedules コンテナ（複数スケジュール）への付与も認識する")
        void 正例_Schedulesコンテナへの付与も認識する() {
            Source src = new Source(BATCH_PATH, """
                package com.mannschaft.app.demo.batch;

                public class DemoFlushBatch {

                    /**
                     * Pod ローカルのバッファを flush する。
                     */
                    @PodLocalScheduled("Pod ローカルのメモリバッファを flush するため、ロックを掛けると敗者 Pod のバッファが永久に残る")
                    @Schedules({@Scheduled(cron = "0 0 3 * * *"), @Scheduled(cron = "0 0 15 * * *")})
                    public void flush() {
                    }
                }
                """);
            assertTrue(analyze(List.of(src)).isEmpty(),
                "複数スケジュール指定のバッチでもマーカーの付与先として正当であるべき: " + analyze(List.of(src)));
        }

        // ── 負例 ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("負例: value() が空文字 → BLANK_REASON")
        void 負例_空文字の理由は違反() {
            assertTrue(hasKind(analyze(List.of(batch(JAVADOC, "\"\"", SCHEDULED))), Kind.BLANK_REASON),
                "空文字の理由は違反であるべき");
        }

        @Test
        @DisplayName("負例: value() が実質のない一言 → TOO_SHORT_REASON")
        void 負例_短すぎる理由は違反() {
            assertTrue(
                hasKind(analyze(List.of(batch(JAVADOC, "\"pod local\"", SCHEDULED))),
                    Kind.TOO_SHORT_REASON),
                "『pod local』のような一言では、ロックを掛けると何が壊れるのか追跡できない");
        }

        @Test
        @DisplayName("負例: value() が定数参照（付与箇所で理由が読めない） → NON_LITERAL_REASON")
        void 負例_定数参照の理由は違反() {
            assertTrue(
                hasKind(analyze(List.of(batch(JAVADOC, "BatchReasons.POD_LOCAL_BUFFER", SCHEDULED))),
                    Kind.NON_LITERAL_REASON),
                "定数参照の理由は違反であるべき");
        }

        @Test
        @DisplayName("負例: Javadoc が無い → NO_JAVADOC")
        void 負例_Javadoc無しは違反() {
            assertTrue(hasKind(analyze(List.of(batch("", GOOD_REASON, SCHEDULED))), Kind.NO_JAVADOC),
                "value() の一行要約だけでは、多重実行時に何が起きるかを後年の監査で追えない");
        }

        @Test
        @DisplayName("負例: Javadoc ではなく実装コメント（/* ... */）しかない → NO_JAVADOC")
        void 負例_実装コメントはJavadocの代わりにならない() {
            assertTrue(
                hasKind(analyze(List.of(batch("    /* pod local */", GOOD_REASON, SCHEDULED))),
                    Kind.NO_JAVADOC),
                "/* ... */ は Javadoc として生成されないため要件を満たさない");
        }

        @Test
        @DisplayName("負例: 中身が空の Javadoc（/***​/）→ NO_JAVADOC")
        void 負例_中身の無いJavadocは違反() {
            assertTrue(
                hasKind(analyze(List.of(batch("    /***/", GOOD_REASON, SCHEDULED))), Kind.NO_JAVADOC),
                "本文が 1 文字も無い Javadoc は根拠にならない");
        }

        @Test
        @DisplayName("負例: @Scheduled の無いメソッドへの付与（死んだ証跡） → NOT_ON_SCHEDULED_METHOD")
        void 負例_非スケジュールメソッドへの付与は違反() {
            assertTrue(
                hasKind(analyze(List.of(batch(JAVADOC, GOOD_REASON, ""))),
                    Kind.NOT_ON_SCHEDULED_METHOD),
                "一次番人が参照しない場所への付与は死んだ証跡であり、"
                    + "『例外を宣言したつもり』の思い込みを生む");
        }

        // ── 偽陽性回避 ────────────────────────────────────────────────────

        @Test
        @DisplayName("偽陽性回避: Javadoc・文字列中のマーカー言及は付与箇所として拾わない")
        void 偽陽性回避_言及は付与箇所ではない() {
            Source doc = new Source(
                "src/main/java/com/mannschaft/app/demo/batch/DocOnlyBatch.java",
                """
                package com.mannschaft.app.demo.batch;
                /**
                 * Pod ローカルのバッチには @PodLocalScheduled を付ける方針である（説明のみ）。
                 */
                public class DocOnlyBatch {
                    static String note() { return "@BatchEndpointExempt"; }
                }
                """);
            assertTrue(analyze(List.of(doc)).isEmpty(),
                "コメント・文字列中の言及は付与箇所として拾わないべき: " + analyze(List.of(doc)));
        }

        // ── パーサ裏取り ──────────────────────────────────────────────────

        @Test
        @DisplayName("裏取り: パーサが付与箇所（メソッド名・理由・Javadoc 有無）を実際に抽出できている")
        void 裏取り_付与箇所の抽出() {
            List<Target> targets = extractTargets(batch(JAVADOC, GOOD_REASON, SCHEDULED));
            assertEquals(1, targets.size(), "付与箇所を 1 件抽出できるべき: " + targets);
            Target t = targets.get(0);
            assertEquals("PodLocalScheduled", t.markerSimpleName);
            assertEquals("flush", t.methodName, "後続の @Scheduled を読み飛ばしてメソッド名を解決できるべき");
            assertTrue(t.reasonIsLiteral, "理由はリテラルとして読み取れるべき");
            assertTrue(t.reason.contains("敗者 Pod"), "理由の本文を読み取れるべき: " + t.reason);
            assertTrue(t.hasJavadoc, "Javadoc の存在を検出できるべき");
            assertTrue(t.onScheduledMethod, "@Scheduled への付与であることを検出できるべき");
        }

        @Test
        @DisplayName("裏取り: BatchEndpointExempt も同じコアで抽出できる（マーカーの取りこぼし防止）")
        void 裏取り_もう一方のマーカーも抽出できる() {
            Source src = new Source(BATCH_PATH, """
                package com.mannschaft.app.demo.batch;

                public class DemoFlushBatch {

                    /**
                     * 5 秒間隔のワーカー。
                     */
                    @BatchEndpointExempt("5 秒間隔の高頻度ワーカーであり、実行履歴を書くと日次バッチの記録が埋没するため")
                    @Scheduled(fixedDelay = 5_000)
                    public void poll() {
                    }
                }
                """);
            List<Target> targets = extractTargets(src);
            assertEquals(1, targets.size(), "抽出対象マーカーの片方だけを見ていないか: " + targets);
            assertEquals("BatchEndpointExempt", targets.get(0).markerSimpleName);
            assertTrue(analyze(List.of(src)).isEmpty(), "要件を満たすため違反 0 件であるべき");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部保持型
    // ═══════════════════════════════════════════════════════════════════════

    /** 走査対象ソース（相対パス＋内容）。 */
    record Source(String relPath, String content) { }

    /** 解決されたメソッド（名前と引数リスト開き括弧の位置）。 */
    record MethodRef(String name, int parenIndex) { }

    /** マーカー付与箇所 1 件。 */
    static final class Target {
        final String relPath;
        final String markerSimpleName;
        final String methodName;
        final String reason;
        final boolean reasonIsLiteral;
        final boolean hasJavadoc;
        final boolean onScheduledMethod;
        final int line;

        Target(String relPath, String markerSimpleName, String methodName, String reason,
               boolean reasonIsLiteral, boolean hasJavadoc, boolean onScheduledMethod, int line) {
            this.relPath = relPath;
            this.markerSimpleName = markerSimpleName;
            this.methodName = methodName;
            this.reason = reason;
            this.reasonIsLiteral = reasonIsLiteral;
            this.hasJavadoc = hasJavadoc;
            this.onScheduledMethod = onScheduledMethod;
            this.line = line;
        }

        @Override
        public String toString() {
            return "@" + markerSimpleName + " on " + methodName + " (" + relPath + ":" + line + ")";
        }
    }

    /** 違反の種別（理由と対処をセットで持つ）。 */
    enum Kind {
        BLANK_REASON(
            "value() に理由が書かれていない（空文字・空白のみ）。理由なき付与は"
                + "『実装漏れの永久凍結』と区別がつかず、番人を骨抜きにするバックドアになる。",
            "ロックを掛ける／実行履歴を書くと具体的に何が壊れるのかを value() に記述してください。"),
        TOO_SHORT_REASON(
            "value() の理由が短すぎる（" + MIN_REASON_LENGTH + " 文字未満）。"
                + "一言では、なぜ例外が正当なのかを後年の監査で再確認できない。",
            "『何が Pod ローカルなのか』『ロック／履歴を入れると何が壊れるのか』まで書き下してください。"),
        NON_LITERAL_REASON(
            "value() が文字列リテラルで書かれていない（定数参照等）。"
                + "付与箇所を読んだだけでは理由が追えず、監査の証跡として機能しない。",
            "理由をその場に読める文字列リテラルで直接記述してください。"),
        NO_JAVADOC(
            "付与対象メソッドに Javadoc が無い。value() は一行の要約に過ぎず、"
                + "多重実行時の挙動・代替の可観測性の担保先は Javadoc がなければ追えない。",
            "対象メソッドに Javadoc を付け、例外が正当である背景を記述してください"
                + "（/* ... */ の実装コメントや本文の無い Javadoc は要件を満たしません）。"),
        NOT_ON_SCHEDULED_METHOD(
            "@Scheduled が付いていないメソッドに付与されている。本マーカーは"
                + "バッチ規約番人の判定に対してのみ意味を持つため、他の場所では死んだ証跡になる。",
            "@Scheduled が付いたバッチメソッドへ移すか、付与を撤去してください。");

        private final String why;
        private final String remedy;

        Kind(String why, String remedy) {
            this.why = why;
            this.remedy = remedy;
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
