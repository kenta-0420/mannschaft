package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.mannschaft.app.common.architecture.SelfScopedEndpointMarkerGuardTest.concatStringLiterals;
import static com.mannschaft.app.common.architecture.SelfScopedEndpointMarkerGuardTest.mask;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: {@link BackgroundFeaturePolicy} の<b>宣言そのものが正しいこと</b>を機械的に強制する
 * （Gate 基盤工事④-A / 受け入れ条件 AC-12〜AC-15）。
 *
 * <h2>本番人が見ないこと（④-D との境界）</h2>
 * <p>本番人は<b>付与された宣言が正しいか</b>だけを見る。
 * 「バックグラウンド入口に宣言を書き忘れていないか」（未付与の {@code @Scheduled} を拒否する）は
 * ④-D で点火する別の番人の責務であり、ここでは作らない。
 * 付与が 0 件の段階で書き忘れ検出を点火すると、既存の 75 バッチが一斉に落ちて
 * CI が赤のまま固まるためである。</p>
 *
 * <h2>なぜ番人が要るのか</h2>
 * <p>この宣言は<b>本番でしか症状が出ない</b>種類の間違いを量産しうる。</p>
 * <ul>
 *   <li><b>キーの綴り間違い（AC-12）</b>: {@code FeatureFlagService.isEnabled} は行が無いキーに
 *       false を返す（{@code orElse(false)} のフェイルクローズ）。よって綴りを間違えると
 *       コンパイルも通り、テストも（そのフラグを stub すれば）通り、
 *       <b>本番のバッチだけが永久に走らなくなる</b>。しかも管理コンソールの
 *       {@code PUT /{flagKey}} は 404 になるため、後から ON にする手段すら無い。
 *       定数参照を許すと付与箇所を読んだだけでは何を要求しているか分からず、
 *       台帳との照合もできないため<b>文字列リテラル必須</b>とする。</li>
 *   <li><b>ALWAYS への gateKeys 併記（AC-13）</b>: ALWAYS は判定を行わない。
 *       にもかかわらずキーが書いてあると、読んだ人間は「ゲートされている」と誤読し、
 *       フラグを OFF にすれば止まると信じてしまう。書けないようにするのが唯一の防御である。</li>
 *   <li><b>モードと付与先の食い違い（AC-14）</b>: {@code SKIP_WHEN_DISABLED} を
 *       リスナーに付ければ「スキップしたつもりでイベントが消えている」ことになり、
 *       {@code DROP_WHEN_DISABLED} をバッチに付ければ「捨てたつもりで実は毎回走っている」
 *       ことになる。とりわけ {@code @SqsListener} は正常終了するとメッセージが ACK され
 *       <b>復旧不能な消失</b>になるため、{@code ALWAYS} 以外を許してはならない。</li>
 *   <li><b>クラスレベル・interface への付与（AC-15）</b>: クラスレベルを許すと、
 *       将来そのクラスに足されたメソッドが<b>暗黙に宣言済み</b>になる。
 *       ④-D の書き忘れ検出はその新メソッドを「宣言済み」と見なして素通しするため、
 *       宣言の網が静かに穴だらけになる。</li>
 * </ul>
 *
 * <h2>方式（金型）</h2>
 * <p>{@code FeatureGateAnnotationKeyGuardTest} の {@code Files.walk} 走査型、
 * および {@code BatchMarkerAnnotationGuardTest} の
 * 「理由はリテラル必須・最小長・Javadoc 併記／<b>免除リストを設けない</b>」に倣う。
 * 文字列・コメントのマスクと文字列リテラル連結の解釈は
 * {@link SelfScopedEndpointMarkerGuardTest} の実績あるユーティリティを再利用する。</p>
 *
 * <p><b>ArchUnit の {@code FreezingArchRule} は使わない。</b>
 * {@code ScheduledBatchGuardTest} / {@code BatchMarkerAnnotationGuardTest} が明示的に
 * 否定している方針に従う。凍結ストアは {@code ./gradlew test --tests "..."} の絞り込み実行で
 * 破壊される事故があり、本テストはファイルを読み取るだけで一切の書き込みを行わない。</p>
 *
 * <h2>空虚 green の防止</h2>
 * <p>本番人の発足時点で {@link BackgroundFeaturePolicy} の付与箇所は本番コードに 0 件であり、
 * パーサが壊れていても実ファイル走査は緑になる。よって {@link 判定ロジック自己検証} が
 * 実ファイル走査と<b>同一コア</b>（{@link #analyze}）に合成入力を通し、
 * AC-12〜AC-15 のそれぞれについて<b>負例で違反が返ること</b>を固定する。</p>
 */
@DisplayName("番人: @BackgroundFeaturePolicy の宣言は正しいこと（Gate基盤工事④-A AC-12〜AC-15）")
class BackgroundFeaturePolicyAnnotationGuardTest {

    private static final Path MAIN_SOURCE_ROOT = Paths.get("src", "main", "java");

    private static final String MARKER = "@" + BackgroundFeaturePolicy.class.getSimpleName();

    /** {@code reason()} に要求する最小文字数（実質のない一言を弾く下限。金型と同値）。 */
    static final int MIN_REASON_LENGTH = 20;

    /** {@code SKIP_WHEN_DISABLED} が併記を要求する注釈。 */
    private static final Set<String> SCHEDULED_ANNOTATIONS = Set.of("Scheduled");

    /** {@code DROP_WHEN_DISABLED} が併記を要求する注釈。 */
    private static final Set<String> LISTENER_ANNOTATIONS =
            Set.of("EventListener", "TransactionalEventListener");

    /** 正常終了が復旧不能な ACK になるため {@code ALWAYS} 以外を許さない注釈。 */
    private static final Set<String> SQS_ANNOTATIONS = Set.of("SqsListener");

    // ═══════════════════════════════════════════════════════════════════
    // 実ファイル走査
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("本番コードの @BackgroundFeaturePolicy が全ての付与要件を満たしていること")
    void 宣言が付与要件を満たしていること() throws IOException {
        Set<String> knownKeys = FeatureGateAnnotationKeyGuardTest.knownFlagKeys();

        assertThat(knownKeys)
                .as("既知フラグキーを1件も収集できなかった。台帳/seed の読み取り経路が壊れている")
                .isNotEmpty();

        List<String> violations = analyze(loadSources(MAIN_SOURCE_ROOT), knownKeys);

        if (violations.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("@BackgroundFeaturePolicy の宣言が規約に反しています。\n")
                .append("検出を緩めて通すことは禁止。宣言側を直すこと（免除リストは設けない）。\n")
                .append("違反一覧:\n");
        for (String v : violations) {
            sb.append("  x ").append(v).append("\n");
        }
        assertThat(violations).as(sb.toString()).isEmpty();
    }

    @Test
    @DisplayName("走査が空振りしていないこと（ソースを1件も読めていない状態での空虚 green 防止）")
    void 走査対象のソースを実際に読めていること() throws IOException {
        List<Source> sources = loadSources(MAIN_SOURCE_ROOT);

        assertThat(sources.size())
                .as("本番ソースの走査件数が少なすぎる（CWD またはソースルートの想定が崩れている）: "
                        + MAIN_SOURCE_ROOT.toAbsolutePath())
                .isGreaterThan(500);
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-15 の構造的な裏取り（宣言そのものの @Target）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-15) @BackgroundFeaturePolicy はメソッドにしか付けられない（@Target が METHOD のみ）")
    void ac15_付与位置はメソッドのみに限定されている() {
        Target target = BackgroundFeaturePolicy.class.getAnnotation(Target.class);

        assertThat(target)
                .as("@Target が無いと型・フィールド・引数など任意の位置に付けられてしまう")
                .isNotNull();
        assertThat(target.value())
                .as("TYPE を許すとクラスレベル付与が可能になり、将来そのクラスに足された"
                        + "メソッドが暗黙に宣言済みになる。④-D の書き忘れ検出がその新メソッドを"
                        + "素通しするため、宣言の網が静かに穴だらけになる")
                .containsExactly(ElementType.METHOD);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 判定コア（純関数。合成入力で偽陰性を暴けるように切り出してある）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ソース群から {@link BackgroundFeaturePolicy} の付与箇所を抽出し、規約違反を列挙する。
     *
     * @param sources   走査対象ソース
     * @param knownKeys 実在すると認めるフラグキーの集合（台帳 ∩ seed）
     * @return 違反一覧（空なら合格）
     */
    static List<String> analyze(List<Source> sources, Set<String> knownKeys) {
        List<String> violations = new ArrayList<>();
        for (Source src : sources) {
            for (Target付与 t : extractTargets(src)) {
                validate(t, knownKeys, violations);
            }
        }
        return violations;
    }

    private static void validate(Target付与 t, Set<String> knownKeys, List<String> violations) {
        String where = t.where();

        // ── AC-15: 付与位置 ─────────────────────────────────────────
        if (t.targetKind != TargetKind.METHOD) {
            violations.add(where + " — メソッド以外（" + t.targetKind + "）に付与されている"
                    + "（クラスレベルを許すと、将来足されたメソッドが暗黙に宣言済みになり、"
                    + "④-D の書き忘れ検出がそれを素通しする）");
            // 付与位置が壊れている場合、以降の属性検査は意味を持たないので打ち切る。
            return;
        }
        if (t.inInterface) {
            violations.add(where + " — インターフェースのメソッドに付与されている"
                    + "（Java の注釈は実装クラスの override へ継承されず AOP が発火しない。"
                    + "実装クラス側へ付け直すこと）");
            return;
        }

        // ── AC-13: reason ───────────────────────────────────────────
        if (!t.reasonIsLiteral) {
            violations.add(where + " — reason が文字列リテラルでない"
                    + "（定数参照は付与箇所を読んだだけで根拠が追えない）: " + t.rawReason);
        } else if (t.reason.isBlank()) {
            violations.add(where + " — reason が空である（この宣言を選んだ根拠を書くこと）");
        } else if (t.reason.strip().length() < MIN_REASON_LENGTH) {
            violations.add(where + " — reason が短すぎる（" + MIN_REASON_LENGTH + "文字以上必要）: "
                    + t.reason.strip());
        }

        // ── mode ───────────────────────────────────────────────────
        if (t.mode == null) {
            violations.add(where + " — mode を解釈できない（列挙定数をそのまま書くこと）: " + t.rawMode);
            return;
        }

        // ── AC-13: ALWAYS への gateKeys 併記禁止 ─────────────────────
        if (t.mode == BackgroundFeatureMode.ALWAYS) {
            if (!t.gateKeyTokens.isEmpty()) {
                violations.add(where + " — ALWAYS に gateKeys が指定されている"
                        + "（ALWAYS は判定を行わない。キーが書いてあると読んだ人間が"
                        + "「ゲートされている」と誤読し、OFF にすれば止まると信じてしまう）: "
                        + t.gateKeyTokens);
            }
        } else {
            // ── AC-13（空・0件）: ゲートするモードにキーが無いのは無意味 ──
            if (t.gateKeyTokens.isEmpty()) {
                violations.add(where + " — " + t.mode + " に gateKeys が無い"
                        + "（判定するキーが無ければ何もゲートされず、宣言だけが残る）");
            }
            // ── AC-12: キーはリテラル必須かつ台帳∩seed に実在 ──
            for (String token : t.gateKeyTokens) {
                String literal = singleStringLiteral(token);
                if (literal == null) {
                    violations.add(where + " — gateKeys が文字列リテラルでない: " + token
                            + "（定数参照は付与箇所を読んだだけで要求が分からず、"
                            + "台帳との照合もできない）");
                    continue;
                }
                if (!knownKeys.contains(literal)) {
                    violations.add(where + " — 実在しないフラグキー: " + literal
                            + "（feature_flags に行が無いキーは isEnabled() が false を返し、"
                            + "本番のバッチだけが永久に走らなくなる。しかも管理コンソールの"
                            + " PUT /{flagKey} が 404 で ON にする手段も無い。"
                            + " docs/inventory/feature-inventory.yaml の release.gate_key と"
                            + " db/migration の seed を確認せよ）");
                }
            }
        }

        // ── AC-14: モードと付与先の対応 ──────────────────────────────
        boolean onScheduled = t.siblingAnnotations.stream().anyMatch(SCHEDULED_ANNOTATIONS::contains);
        boolean onListener = t.siblingAnnotations.stream().anyMatch(LISTENER_ANNOTATIONS::contains);
        boolean onSqs = t.siblingAnnotations.stream().anyMatch(SQS_ANNOTATIONS::contains);

        if (onSqs && t.mode != BackgroundFeatureMode.ALWAYS) {
            violations.add(where + " — @SqsListener に " + t.mode + " が指定されている"
                    + "（SQS リスナーが正常終了するとメッセージが ACK され復旧不能な消失になる。"
                    + " ALWAYS のみ許可）");
        }
        if (t.mode == BackgroundFeatureMode.SKIP_WHEN_DISABLED && !onScheduled) {
            violations.add(where + " — SKIP_WHEN_DISABLED が @Scheduled 以外に付与されている"
                    + "（リスナーに付けると「スキップしたつもりでイベントが消えている」ことになる。"
                    + " 併記されている注釈: " + t.siblingAnnotations + "）");
        }
        if (t.mode == BackgroundFeatureMode.DROP_WHEN_DISABLED && !onListener) {
            violations.add(where + " — DROP_WHEN_DISABLED が @EventListener /"
                    + " @TransactionalEventListener 以外に付与されている"
                    + "（バッチに付けると「捨てたつもりで実は毎回走っている」ことになる。"
                    + " 併記されている注釈: " + t.siblingAnnotations + "）");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // パーサ
    // ═══════════════════════════════════════════════════════════════════

    /** 付与先の種別。 */
    enum TargetKind {
        METHOD, TYPE, OTHER
    }

    /** 1 箇所の付与情報。 */
    record Target付与(
            String relPath,
            int line,
            TargetKind targetKind,
            boolean inInterface,
            List<String> siblingAnnotations,
            BackgroundFeatureMode mode,
            String rawMode,
            List<String> gateKeyTokens,
            String reason,
            String rawReason,
            boolean reasonIsLiteral) {

        String where() {
            return relPath + ":" + line;
        }
    }

    /** 1 ソースから付与箇所をすべて抽出する。 */
    static List<Target付与> extractTargets(Source src) {
        List<Target付与> out = new ArrayList<>();
        if (!src.content.contains(MARKER)) {
            return out;
        }
        String masked = mask(src.content);
        int from = 0;
        while (true) {
            int at = masked.indexOf(MARKER, from);
            if (at < 0) {
                break;
            }
            from = at + MARKER.length();
            // 「@BackgroundFeaturePolicyFoo」のような別トークンを除外する。
            if (from < masked.length() && Character.isJavaIdentifierPart(masked.charAt(from))) {
                continue;
            }

            // 引数を切り出す。
            int cursor = skipWs(masked, from);
            String rawArgs = "";
            if (cursor < masked.length() && masked.charAt(cursor) == '(') {
                int close = matchParen(masked, cursor);
                if (close < 0) {
                    continue; // 括弧が閉じていない（コンパイル不能）ソースは対象外
                }
                rawArgs = src.content.substring(cursor + 1, close);
                cursor = close + 1;
            }

            // 前後の注釈と宣言本体を読む。
            List<String> siblings = new ArrayList<>(annotationsBefore(masked, at));
            Decl decl = declarationAfter(masked, cursor);
            siblings.addAll(decl.annotationsAfter);

            Args args = parseArgs(rawArgs);

            out.add(new Target付与(
                    src.relPath,
                    lineOf(src.content, at),
                    decl.kind,
                    isInterfaceSource(masked),
                    siblings,
                    args.mode,
                    args.rawMode,
                    args.gateKeyTokens,
                    args.reason,
                    args.rawReason,
                    args.reasonIsLiteral));
        }
        return out;
    }

    /** 属性の解析結果。 */
    private record Args(
            BackgroundFeatureMode mode,
            String rawMode,
            List<String> gateKeyTokens,
            String reason,
            String rawReason,
            boolean reasonIsLiteral) {
    }

    private static Args parseArgs(String rawArgs) {
        BackgroundFeatureMode mode = null;
        String rawMode = "";
        List<String> gateKeys = new ArrayList<>();
        String reason = "";
        String rawReason = "";
        boolean reasonIsLiteral = false;

        for (String element : splitTopLevel(rawArgs)) {
            int eq = indexOfTopLevelEquals(element);
            if (eq < 0) {
                continue;
            }
            String name = element.substring(0, eq).strip();
            String value = element.substring(eq + 1).strip();

            switch (name) {
                case "mode" -> {
                    rawMode = value;
                    String simple = value.contains(".")
                            ? value.substring(value.lastIndexOf('.') + 1).strip()
                            : value;
                    for (BackgroundFeatureMode m : BackgroundFeatureMode.values()) {
                        if (m.name().equals(simple)) {
                            mode = m;
                        }
                    }
                }
                case "gateKeys" -> gateKeys.addAll(splitArrayElements(value));
                case "reason" -> {
                    rawReason = value;
                    String literal = concatStringLiterals(value);
                    if (literal != null) {
                        reason = literal;
                        reasonIsLiteral = true;
                    }
                }
                default -> {
                    // 未知の属性はコンパイルエラーになるため、ここでは無視してよい。
                }
            }
        }
        return new Args(mode, rawMode, gateKeys, reason, rawReason, reasonIsLiteral);
    }

    /** 宣言本体の読み取り結果。 */
    private record Decl(TargetKind kind, List<String> annotationsAfter) {
    }

    /**
     * {@code cursor} 以降を読み、後続の注釈と宣言の種別を返す。
     *
     * <p>{@code (} に到達したらメソッド宣言、{@code class} 等のキーワードに到達したら型宣言、
     * それ以外（フィールドの {@code =} や {@code ;}）は OTHER とする。</p>
     */
    private static Decl declarationAfter(String masked, int cursor) {
        List<String> annotations = new ArrayList<>();
        int i = skipWs(masked, cursor);
        int n = masked.length();

        // 後続の注釈をすべて読み飛ばしつつ収集する。
        while (i < n && masked.charAt(i) == '@') {
            int nameStart = i + 1;
            int nameEnd = nameStart;
            while (nameEnd < n && (Character.isJavaIdentifierPart(masked.charAt(nameEnd))
                    || masked.charAt(nameEnd) == '.')) {
                nameEnd++;
            }
            String name = masked.substring(nameStart, nameEnd);
            // 「@interface」は注釈ではなく型宣言キーワードである。
            if ("interface".equals(name)) {
                return new Decl(TargetKind.TYPE, annotations);
            }
            annotations.add(simpleName(name));
            i = skipWs(masked, nameEnd);
            if (i < n && masked.charAt(i) == '(') {
                int close = matchParen(masked, i);
                if (close < 0) {
                    return new Decl(TargetKind.OTHER, annotations);
                }
                i = skipWs(masked, close + 1);
            }
        }

        // 宣言の頭を読む。
        StringBuilder word = new StringBuilder();
        while (i < n) {
            char c = masked.charAt(i);
            if (c == '(') {
                return new Decl(TargetKind.METHOD, annotations);
            }
            if (c == '=' || c == ';') {
                return new Decl(TargetKind.OTHER, annotations);
            }
            if (Character.isJavaIdentifierPart(c)) {
                word.append(c);
            } else {
                String w = word.toString();
                if ("class".equals(w) || "interface".equals(w)
                        || "enum".equals(w) || "record".equals(w)) {
                    return new Decl(TargetKind.TYPE, annotations);
                }
                word.setLength(0);
                if (c == '{' || c == '}') {
                    return new Decl(TargetKind.OTHER, annotations);
                }
            }
            i++;
        }
        return new Decl(TargetKind.OTHER, annotations);
    }

    /**
     * {@code at}（{@code @} の位置）より前に連続して並ぶ注釈の単純名を返す。
     *
     * <p>後ろ向きに 1 注釈ずつ辿る。{@code @RequestMapping({"/a"})} のように
     * 引数に波括弧を含む注釈があるため、区切り文字の後方探索では正しく遡れない。</p>
     */
    private static List<String> annotationsBefore(String masked, int at) {
        List<String> found = new ArrayList<>();
        int i = at;
        while (true) {
            int p = skipWsBack(masked, i - 1);
            if (p < 0) {
                break;
            }
            if (masked.charAt(p) == ')') {
                int open = matchParenBack(masked, p);
                if (open < 0) {
                    break;
                }
                p = skipWsBack(masked, open - 1);
                if (p < 0) {
                    break;
                }
            }
            // 識別子を後方へ読む。
            int end = p + 1;
            while (p >= 0 && (Character.isJavaIdentifierPart(masked.charAt(p))
                    || masked.charAt(p) == '.')) {
                p--;
            }
            if (end == p + 1 || p < 0 || masked.charAt(p) != '@') {
                break;
            }
            found.add(0, simpleName(masked.substring(p + 1, end)));
            i = p;
        }
        return found;
    }

    /**
     * ソースの<b>最初に現れる型宣言キーワード</b>が {@code interface} か（AC-15 の補助判定）。
     *
     * <p>「最初の {@code &#123;} まで」で切る方式は採らない。
     * {@code @RequestMapping(&#123;"/a"&#125;)} のように波括弧を引数に含む注釈が
     * 型宣言より前に並ぶと、頭の切り出しがそこで終わってしまうためである。</p>
     */
    private static boolean isInterfaceSource(String masked) {
        // 「@interface」は注釈型宣言であり通常のインターフェースではないので、先に潰す。
        String s = masked.replace("@interface", "            ");
        int itf = indexOfWord(s, "interface");
        if (itf < 0) {
            return false;
        }
        for (String other : List.of("class", "enum", "record")) {
            int at = indexOfWord(s, other);
            if (at >= 0 && at < itf) {
                return false;
            }
        }
        return true;
    }

    /** {@code word} が識別子境界で独立して現れる最初の位置（無ければ -1）。 */
    private static int indexOfWord(String s, String word) {
        int from = 0;
        while (true) {
            int i = s.indexOf(word, from);
            if (i < 0) {
                return -1;
            }
            boolean leftOk = i == 0 || !Character.isJavaIdentifierPart(s.charAt(i - 1));
            int after = i + word.length();
            boolean rightOk = after >= s.length() || !Character.isJavaIdentifierPart(s.charAt(after));
            if (leftOk && rightOk) {
                return i;
            }
            from = i + word.length();
        }
    }

    // ── 小道具 ────────────────────────────────────────────────────────

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    /** 単一の文字列リテラルなら中身を、そうでなければ null を返す。 */
    static String singleStringLiteral(String token) {
        String t = token.strip();
        if (t.length() < 2 || t.charAt(0) != '"' || t.charAt(t.length() - 1) != '"') {
            return null;
        }
        // 途中でクォートが閉じている（連結・複数トークン）形は単一リテラルではない。
        for (int i = 1; i < t.length() - 1; i++) {
            if (t.charAt(i) == '\\') {
                i++;
                continue;
            }
            if (t.charAt(i) == '"') {
                return null;
            }
        }
        return concatStringLiterals(t);
    }

    /** 注釈引数を最上位のカンマで分割する。 */
    static List<String> splitTopLevel(String args) {
        List<String> out = new ArrayList<>();
        String masked = mask(args);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                addIfNotBlank(out, args.substring(start, i));
                start = i + 1;
            }
        }
        addIfNotBlank(out, args.substring(start));
        return out;
    }

    private static void addIfNotBlank(List<String> out, String s) {
        if (!s.isBlank()) {
            out.add(s.strip());
        }
    }

    /** 配列初期化子（{@code {"A", "B"}}）または単一値を要素へ分解する。 */
    static List<String> splitArrayElements(String value) {
        String v = value.strip();
        if (v.startsWith("{") && v.endsWith("}")) {
            v = v.substring(1, v.length() - 1);
        }
        return splitTopLevel(v);
    }

    /** 最上位の {@code =}（{@code ==} や配列内は除く）の位置。 */
    private static int indexOfTopLevelEquals(String element) {
        String masked = mask(element);
        int depth = 0;
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == '=' && depth == 0) {
                return i;
            }
        }
        return -1;
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

    private static int matchParenBack(String s, int close) {
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

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 判定ロジック自己検証（実ファイルと同一コアに合成入力を通す）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 陽性対照。<b>AC-12〜AC-15 それぞれに負例を置き、番人が実際に落とすことを実証する。</b>
     *
     * <p>偽陰性のまま緑になるのが本番人にとって最大の失敗であるため、
     * 「違反が返ること」だけでなく<b>返ったメッセージの中身</b>まで固定する。</p>
     */
    @Nested
    @DisplayName("判定ロジック自己検証（陽性対照）")
    class 判定ロジック自己検証 {

        private final Set<String> known = new LinkedHashSet<>(
                List.of("FEATURE_SHIFT_ENABLED", "FEATURE_MARKET_ENABLED"));

        private static final String OK_REASON =
                "\"シフト機能はβ非公開のため無効中は走らせない。停止しても既存データの整合性は壊れない。\"";

        private List<String> run(String body) {
            return analyze(List.of(new Source("Synthetic.java",
                    "class Synthetic {\n" + body + "\n}\n")), known);
        }

        // ── 正例（偽陽性が無いこと） ──────────────────────────────────

        @Test
        @DisplayName("正例(i): @Scheduled + SKIP_WHEN_DISABLED + 実在キー + 十分な reason は違反なし")
        void 正例_スキップ宣言() {
            assertThat(run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = %s)
                      public int a() { return 0; }
                    """.formatted(OK_REASON))).isEmpty();
        }

        @Test
        @DisplayName("正例(ii): @TransactionalEventListener + DROP_WHEN_DISABLED は違反なし")
        void 正例_ドロップ宣言() {
            assertThat(run("""
                      @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                          gateKeys = {"FEATURE_SHIFT_ENABLED", "FEATURE_MARKET_ENABLED"},
                          reason = "通知イベントは再生されず失われるが、補助的な通知であり欠落しても整合性は保たれる。")
                      public void onEvent(Object e) {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("正例(iii): ALWAYS は gateKeys 無し・@SqsListener 併記でも違反なし")
        void 正例_ALWAYS宣言() {
            assertThat(run("""
                      @SqsListener("queue-name")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.ALWAYS,
                          reason = "GDPR 削除要求の消化は法令上の義務であり、フラグに関わらず必ず実行する。")
                      public void onMessage(String body) {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("正例(iv): 修飾名（BackgroundFeatureMode 省略）でも mode を解釈できる")
        void 正例_単純名のmode() {
            assertThat(run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = %s)
                      public int a() { return 0; }
                    """.formatted(OK_REASON))).isEmpty();
        }

        // ── AC-12: キーはリテラル必須かつ実在 ─────────────────────────

        @Test
        @DisplayName("負例(AC-12a): gateKeys の定数参照を検出する")
        void 負例_ac12_定数参照() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = Flags.SHIFT,
                          reason = %s)
                      public int a() { return 0; }
                    """.formatted(OK_REASON));

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("文字列リテラルでない").contains("Flags.SHIFT");
        }

        @Test
        @DisplayName("負例(AC-12b): 台帳∩seed に無いキー（綴り間違い）を検出する")
        void 負例_ac12_実在しないキー() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHFIT_ENABLED",
                          reason = %s)
                      public int a() { return 0; }
                    """.formatted(OK_REASON));

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("実在しないフラグキー").contains("FEATURE_SHFIT_ENABLED");
        }

        @Test
        @DisplayName("負例(AC-12c): 複数指定のうち片方だけが実在しない場合も検出する（境界）")
        void 負例_ac12_複数の片方が実在しない() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = {"FEATURE_SHIFT_ENABLED", "FEATURE_UNKNOWN_ENABLED"},
                          reason = %s)
                      public int a() { return 0; }
                    """.formatted(OK_REASON));

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("FEATURE_UNKNOWN_ENABLED");
        }

        // ── AC-13: ALWAYS の gateKeys 禁止 / reason の実質 ──────────────

        @Test
        @DisplayName("負例(AC-13a): ALWAYS に gateKeys を指定した宣言を検出する")
        void 負例_ac13_ALWAYSにgateKeys() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.ALWAYS,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = "GDPR 削除要求の消化は法令上の義務であり、フラグに関わらず必ず実行する。")
                      public int a() { return 0; }
                    """);

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("ALWAYS に gateKeys");
        }

        @Test
        @DisplayName("負例(AC-13b): reason が空文字の宣言を検出する")
        void 負例_ac13_reasonが空() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = "")
                      public int a() { return 0; }
                    """);

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("reason が空");
        }

        @Test
        @DisplayName("負例(AC-13c): reason が最小文字数未満の宣言を検出する（境界）")
        void 負例_ac13_reasonが短すぎる() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = "β非公開のため")
                      public int a() { return 0; }
                    """);

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("reason が短すぎる");
        }

        @Test
        @DisplayName("負例(AC-13d): reason の定数参照を検出する")
        void 負例_ac13_reasonが定数参照() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = Reasons.SHIFT)
                      public int a() { return 0; }
                    """);

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("reason が文字列リテラルでない");
        }

        @Test
        @DisplayName("負例(AC-13e): ゲートするモードなのに gateKeys が空配列の宣言を検出する（0件）")
        void 負例_ac13_gateKeysが空配列() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = {},
                          reason = %s)
                      public int a() { return 0; }
                    """.formatted(OK_REASON));

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("gateKeys が無い");
        }

        @Test
        @DisplayName("負例(AC-13f): gateKeys 属性そのものを省いた宣言を検出する（未指定）")
        void 負例_ac13_gateKeys未指定() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                          reason = %s)
                      public void a() {}
                    """.formatted(OK_REASON));

            assertThat(v).anySatisfy(x -> assertThat(x).contains("gateKeys が無い"));
        }

        // ── AC-14: モードと付与先の対応 ─────────────────────────────

        @Test
        @DisplayName("負例(AC-14a): SKIP_WHEN_DISABLED をリスナーに付けた宣言を検出する")
        void 負例_ac14_スキップをリスナーに付与() {
            List<String> v = run("""
                      @TransactionalEventListener
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = %s)
                      public void onEvent(Object e) {}
                    """.formatted(OK_REASON));

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("SKIP_WHEN_DISABLED が @Scheduled 以外");
        }

        @Test
        @DisplayName("負例(AC-14b): DROP_WHEN_DISABLED をバッチに付けた宣言を検出する")
        void 負例_ac14_ドロップをバッチに付与() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = %s)
                      public void a() {}
                    """.formatted(OK_REASON));

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("DROP_WHEN_DISABLED が @EventListener");
        }

        @Test
        @DisplayName("負例(AC-14c): @SqsListener に SKIP_WHEN_DISABLED を付けた宣言を検出する")
        void 負例_ac14_SQSにスキップ() {
            List<String> v = run("""
                      @SqsListener("queue-name")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = %s)
                      public void onMessage(String body) {}
                    """.formatted(OK_REASON));

            assertThat(v)
                    .as("SQS は正常終了で ACK され復旧不能な消失になるため ALWAYS 以外を許してはならない")
                    .anySatisfy(x -> assertThat(x).contains("@SqsListener"));
        }

        @Test
        @DisplayName("負例(AC-14d): @SqsListener に DROP_WHEN_DISABLED を付けた宣言を検出する")
        void 負例_ac14_SQSにドロップ() {
            List<String> v = run("""
                      @SqsListener("queue-name")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = %s)
                      public void onMessage(String body) {}
                    """.formatted(OK_REASON));

            assertThat(v).anySatisfy(x -> assertThat(x).contains("@SqsListener"));
        }

        @Test
        @DisplayName("負例(AC-14e): 何の入口注釈も無いメソッドへの SKIP_WHEN_DISABLED を検出する")
        void 負例_ac14_入口注釈なし() {
            List<String> v = run("""
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = %s)
                      public int a() { return 0; }
                    """.formatted(OK_REASON));

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("SKIP_WHEN_DISABLED が @Scheduled 以外");
        }

        // ── AC-15: 付与位置 ────────────────────────────────────────

        @Test
        @DisplayName("負例(AC-15a): クラスレベルへの付与を検出する")
        void 負例_ac15_クラスレベル付与() {
            List<String> v = analyze(List.of(new Source("Synthetic.java", """
                    @BackgroundFeaturePolicy(
                        mode = BackgroundFeatureMode.ALWAYS,
                        reason = "GDPR 削除要求の消化は法令上の義務であり、フラグに関わらず必ず実行する。")
                    class Synthetic {
                      public int a() { return 0; }
                    }
                    """)), known);

            assertThat(v)
                    .as("クラスレベルを許すと、将来足されたメソッドが暗黙に宣言済みになる")
                    .hasSize(1);
            assertThat(v.get(0)).contains("メソッド以外").contains("TYPE");
        }

        @Test
        @DisplayName("負例(AC-15b): interface のメソッドへの付与を検出する")
        void 負例_ac15_インターフェースメソッド付与() {
            List<String> v = analyze(List.of(new Source("Synthetic.java", """
                    interface Synthetic {
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = "シフト機能はβ非公開のため無効中は走らせない。停止しても整合性は壊れない。")
                      int a();
                    }
                    """)), known);

            assertThat(v)
                    .as("Java の注釈は実装クラスの override へ継承されず、"
                            + "インターフェースに付けても AOP が一切発火しない")
                    .anySatisfy(x -> assertThat(x).contains("インターフェース"));
        }

        @Test
        @DisplayName("負例(AC-15c): 注釈型（@interface）宣言への付与を検出する")
        void 負例_ac15_注釈型付与() {
            List<String> v = analyze(List.of(new Source("Synthetic.java", """
                    @BackgroundFeaturePolicy(
                        mode = BackgroundFeatureMode.ALWAYS,
                        reason = "GDPR 削除要求の消化は法令上の義務であり、フラグに関わらず必ず実行する。")
                    @interface Synthetic {
                    }
                    """)), known);

            assertThat(v).anySatisfy(x -> assertThat(x).contains("メソッド以外"));
        }

        // ── パーサの裏取り ────────────────────────────────────────

        @Test
        @DisplayName("裏取り: 波括弧を含む先行注釈があっても後方の @Scheduled を見失わない")
        void 裏取り_波括弧を含む先行注釈を跨げる() {
            assertThat(run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @SchedulerLock(name = "lock", lockAtMostFor = "PT10M")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_SHIFT_ENABLED",
                          reason = %s)
                      public int a() { return 0; }
                    """.formatted(OK_REASON)))
                    .as("後方探索が @SchedulerLock で止まると @Scheduled を見失い、"
                            + "正しい宣言を AC-14 違反として誤検出する")
                    .isEmpty();
        }

        @Test
        @DisplayName("裏取り: コメント内の @BackgroundFeaturePolicy は付与として拾わない")
        void 裏取り_コメント内は拾わない() {
            assertThat(run("""
                      // @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS, reason = "x")
                      public int a() { return 0; }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("裏取り: 1ファイル内の複数付与をすべて抽出する")
        void 裏取り_複数付与を抽出する() {
            List<String> v = run("""
                      @Scheduled(cron = "0 0 3 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_BAD_ONE",
                          reason = %s)
                      public int a() { return 0; }

                      @Scheduled(cron = "0 0 4 * * *")
                      @BackgroundFeaturePolicy(
                          mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                          gateKeys = "FEATURE_BAD_TWO",
                          reason = %s)
                      public int b() { return 0; }
                    """.formatted(OK_REASON, OK_REASON));

            assertThat(v).hasSize(2);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ファイル読み込み
    // ═══════════════════════════════════════════════════════════════════

    /** 走査対象ソース1件（リポジトリ相対パスと本文）。 */
    record Source(String relPath, String content) {
    }

    private static List<Source> loadSources(Path root) throws IOException {
        assertThat(Files.isDirectory(root))
                .as("ソースルートが見つからない: " + root.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）")
                .isTrue();
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
}
