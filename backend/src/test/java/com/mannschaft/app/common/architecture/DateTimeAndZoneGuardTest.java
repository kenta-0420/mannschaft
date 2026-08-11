package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * 番人: 時刻設計方針（{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md}、
 * PR #2698・main 着地コミット {@code 16fb42d78}）に反する古い流儀を、新規コードが使えないようにする。
 *
 * <h2>禁止する4種（issue #2700）</h2>
 * <ol>
 *   <li>引数なしの {@code LocalDateTime.now()} / {@code LocalDate.now()} / {@code LocalTime.now()}
 *       （暗黙に JVM 既定ゾーンへ依存する。ゾーンは常に明示すべき）</li>
 *   <li>{@code ZoneId.systemDefault()}（同上）</li>
 *   <li>{@code ZoneId.of("Asia/Tokyo")} 等、タイムゾーンのリテラル直書き
 *       （現状 JST 固定だが、JST 以外のチームが来た瞬間に壊れる）</li>
 *   <li>新規に追加される {@code LocalDateTime} 型のフィールド／DTO プロパティ
 *       （瞬間なのか壁時計なのか型から判別できない）</li>
 * </ol>
 *
 * <h2>本テストは ArchUnit ではない</h2>
 * <p>「メソッド呼び出しの引数が 0 個か」「引数が文字列リテラルか」「フィールド宣言かローカル変数か」は
 * いずれもバイトコード解析（ArchUnit）では素直に判定できない、または判定できても検出結果を
 * 「新規のみ fail・既存は凍結」という粒度で扱うのに不向きなため、
 * {@link PagingTotalCountSizeGuardTest} と同じ<b>ソース走査型</b>で書いた。
 * したがって ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）は一切使わず、
 * 本クラス専用の凍結リスト（{@code src/test/resources/datetime_guard/*.txt}）を4種別に用いる。
 * {@code --tests} 絞り込み実行をしても ArchUnit 側の凍結ストアは巻き込まない。</p>
 *
 * <h2>凍結リストの位置づけ【必読】— これは CMP-023 で返済する借金の台帳である</h2>
 * <p>既存コードは4種合計で数千箇所に及ぶ（{@code TimeZoneConfig} が JVM 既定ゾーンを
 * {@code Asia/Tokyo} に固定しているため、引数なし {@code now()} は現在「たまたま」正しく動いている。
 * 本番人は今あるバグを見つけるためではなく、<b>これ以上増やさない</b>ためのものである）。
 * 一斉に赤くすると番人自体が機能しないため、既存分は {@code src/test/resources/datetime_guard/}
 * 配下の4ファイルへ凍結する。</p>
 * <p><b>この凍結は「対処済み」を意味しない。CMP-023（{@code docs/task-list.md}）で計画的に
 * 是正し、削っていく対象である。</b> 新規に同じ型のコードを書いて凍結リストへ追記することは
 * 禁止する。新規違反は本テストを fail させ、設計方針に沿った書き方（{@code Instant} /
 * {@code OffsetDateTime} での起きた瞬間の保持、{@code LocalDate}/{@code LocalTime} + 明示 TZ
 * での壁時計の保持）へ是正すること。凍結件数が<b>増えたら</b>本テストと
 * {@link #freezeCountsDoNotExceedRecordedCeiling} が fail する。件数が<b>減った</b>場合は凍結リストから
 * 実在しなくなったキー（stale entry）を削除する形で追随させる（chip-away。
 * 認可監査戦役が {@code EXPECTED_LINES_*} で採った方式の踏襲。795→0まで返済した実績がある）。</p>
 *
 * <h2>走査ロジックの正しさ</h2>
 * <p>CMP-022 の監査で、Java ソースを走査する番人群（第二波）にブロックコメント・文字列リテラル・
 * テキストブロックの誤認という同型の欠陥が見つかった。本クラスは {@link JavaSourceScanningUtils}
 * を使い同じ欠陥を踏まないが、走査ロジック自体の正しさは
 * {@link DateTimeAndZoneGuardScanningLogicTest} で固定する（コメント・文字列内の記述は
 * 誤検出しない／フィールド宣言とローカル変数・メソッド引数は区別する、を回帰テストで実証）。</p>
 *
 * <h2>フィールド判定は正規表現の「メソッド範囲」検出をやめ、波括弧の深さ走査で行う</h2>
 * <p>実装初期に「{@code public|private|protected} を必須にしたメソッド宣言正規表現」で
 * メソッド範囲を検出していたが、package-private メソッドの引数がメソッド外側（＝フィールド）と
 * 誤認される欠陥が {@link DateTimeAndZoneGuardScanningLogicTest} 自身で見つかった。
 * 次に修飾子を任意にしたところ、今度は正規表現エンジンの {@code Curly}/{@code Branch} が
 * 入れ子になり、全 production ソース走査で 30 分超 CPU を飽和させたまま終わらない
 * <b>破滅的バックトラック</b>を引き起こした（jstack で確認・実測）。修飾子必須・任意のどちらに
 * 振っても壊れるのは調整の問題ではなく道具の選択が誤っているため、<b>正規表現でメソッド範囲を
 * 特定するのをやめ</b>、{@link #buildBraceFrames} による<b>波括弧の深さを数える線形走査</b>に
 * 置き換えた。修飾子の有無に一切依存しないため package-private も匿名クラスも正しく扱え、
 * 後方に辿るヘッダ文字列は直前の {@code ;}/{@code {}/{@code }} までに区切られる
 * ため長さが有界であり、バックトラックが起こらない。全 production ソース走査を
 * {@link #noNewOldStyleDateTimeUsage} 内で {@code assertTimeout} により有限時間で終わることを
 * 固定し、同種の事故の再発を検知できるようにしている。</p>
 *
 * @see PagingTotalCountSizeGuardTest
 * @see DateTimeAndZoneGuardScanningLogicTest
 */
@DisplayName("番人: 時刻のnow()/ZoneId直書き/LocalDateTimeフィールドが新規に増えていないこと（CMP-023返済対象の凍結台帳付き）")
class DateTimeAndZoneGuardTest {

    // ────────────────────────────────────────────────────────────
    // 検出パターン（package-private でスキャンロジックテストから再利用）
    // ────────────────────────────────────────────────────────────

    static final Pattern NO_ARG_NOW =
            Pattern.compile("\\b(LocalDateTime|LocalDate|LocalTime)\\.now\\s*\\(\\s*\\)");
    static final Pattern ZONE_SYSTEM_DEFAULT =
            Pattern.compile("\\bZoneId\\.systemDefault\\s*\\(\\s*\\)");
    static final Pattern ZONE_LITERAL =
            Pattern.compile("\\bZoneId\\.of\\s*\\(\\s*\"[^\"]*\"");
    /** フィールド／DTOプロパティ宣言候補。ローカル変数と区別する処理は brace-depth 走査側。 */
    static final Pattern LOCAL_DATE_TIME_DECL =
            Pattern.compile("\\bLocalDateTime\\s+([A-Za-z_$][\\w$]*)\\s*[;=,)]");
    /** {@code record Foo(...)} のヘッダ（コンポーネント宣言）検出用。 */
    static final Pattern RECORD_HEADER =
            Pattern.compile("\\brecord\\s+[A-Za-z_$][\\w$]*\\s*(?:<[^>]*>)?\\s*\\(");
    /** ブロック直前ヘッダに型宣言キーワードを含むかどうか（class/interface/enum/record の本体判定）。 */
    static final Pattern TYPE_BODY_HEAD = Pattern.compile("\\b(class|interface|enum|record)\\b");
    /** ヘッダ末尾の「識別子(...)」形（メソッド／コンストラクタ／制御構文いずれも同形）からラベルを拾う。 */
    static final Pattern TRAILING_CALL_NAME =
            Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\([^()]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*$");
    private static final java.util.Set<String> CONTROL_KEYWORDS =
            java.util.Set.of("if", "for", "while", "switch", "catch", "synchronized", "do");

    enum Category {
        NO_ARG_NOW("引数なしの LocalDateTime/LocalDate/LocalTime.now()",
                Paths.get("src", "test", "resources", "datetime_guard", "no_arg_now_freeze.txt")),
        ZONE_SYSTEM_DEFAULT("ZoneId.systemDefault()",
                Paths.get("src", "test", "resources", "datetime_guard", "zone_system_default_freeze.txt")),
        ZONE_LITERAL("ZoneId.of(\"...\") のタイムゾーンリテラル直書き",
                Paths.get("src", "test", "resources", "datetime_guard", "zone_literal_freeze.txt")),
        LOCAL_DATE_TIME_FIELD("LocalDateTime型のフィールド／DTOプロパティ",
                Paths.get("src", "test", "resources", "datetime_guard", "localdatetime_field_freeze.txt"));

        final String description;
        final Path freezeFile;

        Category(String description, Path freezeFile) {
            this.description = description;
            this.freezeFile = freezeFile;
        }
    }

    record Violation(Category category, String fqcn, String location, int seq, int line, String snippet) {
        String key() {
            return category.name() + "|" + fqcn + "#" + location + "#" + seq;
        }

        String describe() {
            return key() + " (L" + line + "): " + snippet;
        }
    }

    // ────────────────────────────────────────────────────────────
    // production ソースルート・凍結ファイル解決（backend/実行・リポジトリルート実行の両対応）
    // ────────────────────────────────────────────────────────────

    private static Path sourceRoot() {
        for (String candidate : new String[]{"src/main/java", "backend/src/main/java"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "src/main/java が見つからない（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    private static Path resolveFreezeFile(Path relative) {
        for (Path candidate : new Path[]{relative, Paths.get("backend").resolve(relative)}) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "凍結リストが見つからない: " + relative + "（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    private static Set<String> readFreezeList(Path relative) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(resolveFreezeFile(relative), StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            keys.add(trimmed);
        }
        return keys;
    }

    // ────────────────────────────────────────────────────────────
    // テスト本体: 4種を1テストで検証（凍結ファイルは種別ごとに独立）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("引数なしnow()/ZoneId.systemDefault()/ZoneIdリテラル/LocalDateTimeフィールドの新規追加は無い（既存は種別ごとの凍結リストのみ許容）")
    void noNewOldStyleDateTimeUsage() throws IOException {
        Path root = sourceRoot();
        // 破滅的バックトラック事故（線形走査への置き換え前に実測）の再発防止。全 production
        // ソース走査が有限時間で終わることを固定する。走査は O(n) のため 30 秒は十分な余裕。
        List<Violation> all = assertTimeout(Duration.ofSeconds(30), () -> collectViolations(root));

        assertThat(all)
                .as("production コードから時刻関連の走査対象を1件も検出できなかった"
                        + "（走査パスの前提が壊れた可能性）")
                .isNotEmpty();

        Map<Category, List<Violation>> byCategory = new HashMap<>();
        for (Category c : Category.values()) {
            byCategory.put(c, new ArrayList<>());
        }
        for (Violation v : all) {
            byCategory.get(v.category()).add(v);
        }

        StringBuilder failure = new StringBuilder();
        for (Category category : Category.values()) {
            List<Violation> found = byCategory.get(category);
            Set<String> frozen = readFreezeList(category.freezeFile);
            Set<String> foundKeys = found.stream().map(Violation::key)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            List<String> newViolations = found.stream()
                    .filter(v -> !frozen.contains(v.key()))
                    .map(Violation::describe)
                    .sorted()
                    .toList();
            if (!newViolations.isEmpty()) {
                failure.append("【新規違反: ").append(category.description).append("】").append(System.lineSeparator())
                        .append(("『対処療法禁止・根治治療』原則により、"
                                + "docs/architecture/datetime_policy_utc_instant_vs_wallclock.md の方針"
                                + "（起きた瞬間はInstant/OffsetDateTime、壁時計はLocalDate/LocalTime+明示TZ）"
                                + "へ是正すること。既存の負債として凍結する場合は %s に追記すること"
                                + "（原則非推奨。本テストのJavadoc『凍結リストの位置づけ』を参照。"
                                + "CMP-023 で返済する借金の台帳であり免罪符ではない）。%n")
                                .formatted(category.freezeFile))
                        .append(String.join(System.lineSeparator(), newViolations))
                        .append(System.lineSeparator()).append(System.lineSeparator());
            }

            List<String> staleFrozenEntries = frozen.stream()
                    .filter(k -> !foundKeys.contains(k))
                    .sorted()
                    .toList();
            if (!staleFrozenEntries.isEmpty()) {
                failure.append("【凍結リストの陳腐化エントリ: ").append(category.description).append("】%n".formatted())
                        .append(("凍結リストに、実コードにはもう存在しない古いエントリが残っている。"
                                + "根治済みなら %s から該当行を削除すること（chip-away）。"
                                + "根治していないのに消えた場合はメソッド名変更等で検出キーがずれた可能性があり要調査。"
                                + "ずれたエントリ: %s%n")
                                .formatted(category.freezeFile, staleFrozenEntries))
                        .append(System.lineSeparator());
            }
        }

        assertThat(failure.toString()).as(failure.toString()).isEmpty();
    }

    /**
     * 凍結件数の<b>スナップショット</b>（借金の台帳。上限ではなく一致すべき実件数）。
     * <b>増える方向</b>にずれても<b>減る方向</b>にずれても、このテストは fail する
     * （減った場合は是正が反映された証拠なので、{@code EXPECTED_FROZEN_*} を新しい実件数へ
     * 追随更新する。増えた場合は凍結ファイルへの新規追記であり禁止）。判定ロジックは
     * {@link #freezeCountMismatches} に切り出し、{@link DateTimeAndZoneGuardScanningLogicTest}
     * 相当の回帰テストで「1件増えたら検出する」ことを直接実証している。
     */
    @Test
    @DisplayName("凍結件数（CMP-023返済台帳）が記録済みスナップショットと一致している（増減とも検知）")
    void freezeCountsMatchRecordedSnapshot() throws IOException {
        Map<Category, Integer> actualCounts = new HashMap<>();
        for (Category category : Category.values()) {
            actualCounts.put(category, readFreezeList(category.freezeFile).size());
        }
        List<String> mismatches = freezeCountMismatches(actualCounts, expectedFrozenSnapshot());
        assertThat(mismatches)
                .as("凍結件数の実測値が記録済みスナップショット(EXPECTED_FROZEN_*)と一致しない。"
                        + "増えた場合は新規の凍結追記であり禁止（CMP-023返済台帳）。是正すること。"
                        + "減った場合は是正の反映なので、本テストの EXPECTED_FROZEN_* 定数を"
                        + "新しい実件数へ更新して返済の進捗を記録すること。差分: %s".formatted(mismatches))
                .isEmpty();
    }

    /** {@link #freezeCountsMatchRecordedSnapshot} の判定本体。ファイルI/Oを含まないため単体テスト可能。 */
    static List<String> freezeCountMismatches(Map<Category, Integer> actualCounts, Map<Category, Integer> expected) {
        List<String> mismatches = new ArrayList<>();
        for (Category category : Category.values()) {
            int actual = actualCounts.get(category);
            int snapshot = expected.get(category);
            if (actual != snapshot) {
                mismatches.add("%s: 実件数=%d, 記録スナップショット=%d (%s)".formatted(
                        category.name(), actual, snapshot, actual > snapshot ? "増加=禁止" : "減少=要追随更新"));
            }
        }
        return mismatches;
    }

    private static Map<Category, Integer> expectedFrozenSnapshot() {
        return Map.of(
                Category.NO_ARG_NOW, EXPECTED_FROZEN_NO_ARG_NOW,
                Category.ZONE_SYSTEM_DEFAULT, EXPECTED_FROZEN_ZONE_SYSTEM_DEFAULT,
                Category.ZONE_LITERAL, EXPECTED_FROZEN_ZONE_LITERAL,
                Category.LOCAL_DATE_TIME_FIELD, EXPECTED_FROZEN_LOCAL_DATE_TIME_FIELD);
    }

    /**
     * CMP-023 返済台帳。凍結ファイルの実件数のスナップショット（本 issue #2700 実装時点、
     * 波括弧深さ走査（{@link #buildBraceFrames}）による最終版ロジックで測定）。
     * 認可監査戦役の {@code EXPECTED_LINES_*} 方式を踏襲する。
     * 件数が減った場合はここも追随して更新し、返済の進捗を数値で残すこと。
     */
    private static final int EXPECTED_FROZEN_NO_ARG_NOW = 1677;
    private static final int EXPECTED_FROZEN_ZONE_SYSTEM_DEFAULT = 28;
    private static final int EXPECTED_FROZEN_ZONE_LITERAL = 53;
    private static final int EXPECTED_FROZEN_LOCAL_DATE_TIME_FIELD = 2658;

    // ────────────────────────────────────────────────────────────
    // 検出本体（package-private: スキャンロジックテストから直接呼び出す）
    // ────────────────────────────────────────────────────────────

    static List<Violation> collectViolations(Path root) {
        List<Violation> violations = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            violations.addAll(collectViolationsInFile(read(file), toFqcn(root, file)));
        }
        return violations;
    }

    private enum FrameKind { TYPE_BODY, CODE_BODY }

    private record BraceFrame(int start, int end, FrameKind kind, String label) {
        boolean contains(int offset) {
            return start <= offset && offset < end;
        }

        int span() {
            return end - start;
        }
    }

    /** 1ファイル分の走査。スキャンロジックの回帰テストから合成ソースに対しても直接呼べる。 */
    static List<Violation> collectViolationsInFile(String raw, String fqcn) {
        List<Violation> violations = new ArrayList<>();

        // コメント・文字列/テキストブロックの中身を消した版（now()/systemDefault()/フィールド宣言用）。
        String maskedLiterals = JavaSourceScanningUtils.maskCommentsAndLiterals(raw);
        // コメントのみ消し、文字列は残す版（ZoneId.of("Asia/Tokyo") のリテラル値を読む用）。
        String maskedComments = JavaSourceScanningUtils.maskCommentsOnly(raw);

        List<BraceFrame> frames = buildBraceFrames(maskedLiterals);
        int[] parenDepth = parenDepthAtEachOffset(maskedLiterals);

        addCallViolations(violations, maskedLiterals, NO_ARG_NOW, Category.NO_ARG_NOW, fqcn, frames, raw);
        addCallViolations(violations, maskedLiterals, ZONE_SYSTEM_DEFAULT, Category.ZONE_SYSTEM_DEFAULT, fqcn, frames, raw);
        addCallViolations(violations, maskedComments, ZONE_LITERAL, Category.ZONE_LITERAL, fqcn, frames, raw);
        addFieldViolations(violations, maskedLiterals, fqcn, frames, parenDepth, raw);

        return violations;
    }

    private static void addCallViolations(List<Violation> violations, String scanText, Pattern pattern,
            Category category, String fqcn, List<BraceFrame> frames, String raw) {
        Matcher m = pattern.matcher(scanText);
        Map<String, Integer> seqByLocation = new HashMap<>();
        while (m.find()) {
            String location = enclosingLabel(frames, m.start());
            int seq = seqByLocation.merge(location, 1, Integer::sum);
            int line = lineNumber(raw, m.start());
            violations.add(new Violation(category, fqcn, location, seq, line, snippet(raw, m.start(), m.end())));
        }
    }

    /**
     * フィールド／DTOプロパティ（record コンポーネント含む）を検出する。
     * <ul>
     *   <li>クラス／インターフェース／enum 本体（型宣言の直下）にある宣言のみを対象とする
     *       （メソッド本体・初期化ブロック・匿名クラス内は除外）</li>
     *   <li>丸括弧の中（メソッド／コンストラクタの引数リスト）にある宣言も除外する</li>
     *   <li>{@code record Foo(LocalDateTime x)} のヘッダ（コンポーネントリスト）は丸括弧の中だが、
     *       DTOプロパティそのものなので別途明示的に検出対象へ含める</li>
     * </ul>
     */
    private static void addFieldViolations(List<Violation> violations, String scanText, String fqcn,
            List<BraceFrame> frames, int[] parenDepth, String raw) {
        List<int[]> fieldSpans = new ArrayList<>(); // {start, end}

        Matcher m = LOCAL_DATE_TIME_DECL.matcher(scanText);
        while (m.find()) {
            int offset = m.start();
            if (parenDepth[offset] != 0) {
                continue; // メソッド／コンストラクタ引数リストの中（record ヘッダは別途処理）。
            }
            BraceFrame innermost = innermostFrame(frames, offset);
            if (innermost != null && innermost.kind() != FrameKind.TYPE_BODY) {
                continue; // メソッド本体・初期化ブロック・匿名クラス内のローカル変数。
            }
            fieldSpans.add(new int[]{offset, m.end()});
        }

        Matcher recordHeader = RECORD_HEADER.matcher(scanText);
        while (recordHeader.find()) {
            int openParen = recordHeader.end() - 1;
            int closeParen = findMatchingParen(scanText, openParen);
            if (closeParen < 0) {
                continue;
            }
            Matcher component = LOCAL_DATE_TIME_DECL.matcher(scanText.substring(openParen, closeParen + 1));
            while (component.find()) {
                fieldSpans.add(new int[]{openParen + component.start(), openParen + component.end()});
            }
        }

        fieldSpans.sort((a, b) -> Integer.compare(a[0], b[0]));
        int seq = 0;
        for (int[] span : fieldSpans) {
            seq++;
            int line = lineNumber(raw, span[0]);
            violations.add(new Violation(Category.LOCAL_DATE_TIME_FIELD, fqcn, "(class-level)", seq, line,
                    snippet(raw, span[0], span[1])));
        }
    }

    private static BraceFrame innermostFrame(List<BraceFrame> frames, int offset) {
        BraceFrame best = null;
        for (BraceFrame f : frames) {
            if (f.contains(offset) && (best == null || f.span() < best.span())) {
                best = f;
            }
        }
        return best;
    }

    private static String enclosingLabel(List<BraceFrame> frames, int offset) {
        BraceFrame f = innermostFrame(frames, offset);
        return f != null ? f.label() : "(top-level)";
    }

    /**
     * 波括弧の深さを線形走査して {@link BraceFrame} 一覧を作る。正規表現による「メソッド範囲」
     * 検出（修飾子必須で package-private を取りこぼす／修飾子任意で破滅的バックトラックを起こす）
     * の両方の失敗を踏まえ、<b>波括弧の対応と、直前ヘッダの短い有界な後方走査だけ</b>で判定する。
     * 全体は O(n)（後方走査は直前の {@code ;}/{@code {}/{@code }} までに区切られ有界）。
     */
    private static List<BraceFrame> buildBraceFrames(String code) {
        List<BraceFrame> frames = new ArrayList<>();
        Deque<Integer> openStack = new ArrayDeque<>();
        int n = code.length();
        for (int i = 0; i < n; i++) {
            char c = code.charAt(i);
            if (c == '{') {
                openStack.push(i);
            } else if (c == '}') {
                if (!openStack.isEmpty()) {
                    int start = openStack.pop();
                    frames.add(classifyBrace(code, start, i + 1));
                }
            }
        }
        return frames;
    }

    private static BraceFrame classifyBrace(String code, int bracePos, int endExclusive) {
        int j = bracePos - 1;
        while (j >= 0) {
            char c = code.charAt(j);
            if (c == ';' || c == '{' || c == '}') {
                break;
            }
            j--;
        }
        String header = code.substring(j + 1, bracePos);
        if (TYPE_BODY_HEAD.matcher(header).find()) {
            return new BraceFrame(bracePos, endExclusive, FrameKind.TYPE_BODY, "(class-level)");
        }
        return new BraceFrame(bracePos, endExclusive, FrameKind.CODE_BODY, extractLabel(header));
    }

    private static String extractLabel(String header) {
        Matcher m = TRAILING_CALL_NAME.matcher(header);
        if (m.find()) {
            String name = m.group(1);
            if (CONTROL_KEYWORDS.contains(name)) {
                return "(制御ブロック:" + name + ")";
            }
            return name;
        }
        return "(不明ブロック)";
    }

    private static int[] parenDepthAtEachOffset(String code) {
        int n = code.length();
        int[] depth = new int[n + 1];
        int d = 0;
        for (int i = 0; i < n; i++) {
            depth[i] = d;
            char c = code.charAt(i);
            if (c == '(') {
                d++;
            } else if (c == ')') {
                d = Math.max(0, d - 1);
            }
        }
        depth[n] = d;
        return depth;
    }

    private static int findMatchingParen(String s, int openParenIndex) {
        int depth = 0;
        for (int i = openParenIndex; i < s.length(); i++) {
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

    private static int lineNumber(String raw, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < raw.length(); i++) {
            if (raw.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String snippet(String raw, int start, int end) {
        int s = Math.max(0, start);
        int e = Math.min(raw.length(), Math.max(end, start + 1));
        return raw.substring(s, e).strip();
    }

    private static String toFqcn(Path root, Path file) {
        Path rel = root.relativize(file);
        String s = rel.toString().replace('\\', '/').replace('/', '.');
        return s.substring(0, s.length() - ".java".length());
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
