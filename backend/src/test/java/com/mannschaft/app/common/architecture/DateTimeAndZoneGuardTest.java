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
import java.util.LinkedHashMap;
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
 * での壁時計の保持）へ是正すること。</p>
 *
 * <h2>凍結キーは「クラス単位の件数」であり、メソッド名を含まない【必読・PR #2725 の事故から】</h2>
 * <p>初版は凍結キーに {@code <FQCN>#<メソッド名>#<出現順>} を使っていた。ところが main が
 * 30分おきに進むこの基盤では、<b>本 issue と無関係なリファクタでメソッド名を変えただけ</b>で
 * そのメソッド内の既存の凍結エントリが「実在しないキー」（陳腐化）になり、<b>同時に同じ既存負債が
 * 「新規違反」としても検出される</b>という事故が実際に起きた（PR #2725、
 * {@code RecommendationService#getRecommendations} のリファクタで CI が赤くなった）。
 * <b>「既存の負債を含むメソッドの名前を変えられない」番人は、是正を促すはずが是正を妨げる。</b>
 * よって凍結キーから<b>メソッド名・出現順を完全に排除</b>し、{@code <カテゴリ>|<FQCN>|<件数>}
 * という<b>クラス単位の件数</b>だけを凍結する形に改めた（{@link #classCountMismatches}）。
 * メソッド名の変更・メソッドの移動・クラス内での出現順の入れ替えでは、そのクラスの検出件数
 * そのものは変わらないため CI は落ちない。一方で「1件でも増えたら fail」という番人の本質は
 * 完全に保たれる（{@link DateTimeAndZoneGuardScanningLogicTest} の
 * {@code classCountMismatches_} 系回帰テストで、増加時の検出とメソッド名変更時の非検出の
 * 両方を実証している）。<b>ここへメソッド名を書き戻すことを検討する場合は、必ずこの事故の経緯
 * を読んでから判断すること。</b></p>
 * <p>唯一の副作用: 同一カテゴリの違反をクラスAからクラスBへ<b>移動</b>すると、カテゴリ合計は
 * 変わらなくても A（件数減）・B（件数増）の両方で台帳とのズレが検出される。これは意図どおりの
 * 挙動である（クラス単位で見れば実際に構成が変わっているため、台帳の更新が必要）。</p>
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
    private static final Set<String> CONTROL_KEYWORDS =
            Set.of("if", "for", "while", "switch", "catch", "synchronized", "do");

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
        /** 参考情報つきの人間可読な説明（凍結キーには使わない。メソッド名は表示専用）。 */
        String describe() {
            return "%s#%s (L%d): %s".formatted(fqcn, location, line, snippet);
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

    /**
     * 凍結ファイルを {@code <カテゴリ>|<FQCN>|<件数>} 形式でクラス単位に読む。
     * メソッド名・出現順は凍結キーに含めない（理由はクラス Javadoc『凍結キーはクラス単位の件数』参照）。
     */
    private static Map<String, Integer> readFreezeClassCounts(Category category) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Path path = resolveFreezeFile(category.freezeFile);
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 3);
            if (parts.length != 3 || !parts[0].equals(category.name())) {
                throw new IllegalStateException(
                        "凍結ファイルの行形式が不正: " + path + " の行 \"" + trimmed + "\"。"
                                + "期待形式: " + category.name() + "|<FQCN>|<件数>");
            }
            counts.merge(parts[1], Integer.parseInt(parts[2]), Integer::sum);
        }
        return counts;
    }

    // ────────────────────────────────────────────────────────────
    // テスト本体: 4種を1テストで検証（凍結ファイルは種別ごとに独立）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("引数なしnow()/ZoneId.systemDefault()/ZoneIdリテラル/LocalDateTimeフィールドの新規追加は無い（既存は種別ごとの凍結リストのクラス単位件数のみ許容）")
    void noNewOldStyleDateTimeUsage() throws IOException {
        Path root = sourceRoot();
        // 破滅的バックトラック事故（線形走査への置き換え前に実測）の再発防止。全 production
        // ソース走査が有限時間で終わることを固定する。走査は O(n) のため 30 秒は十分な余裕。
        List<Violation> all = assertTimeout(Duration.ofSeconds(30), () -> collectViolations(root));

        assertThat(all)
                .as("production コードから時刻関連の走査対象を1件も検出できなかった"
                        + "（走査パスの前提が壊れた可能性）")
                .isNotEmpty();

        StringBuilder failure = new StringBuilder();
        for (Category category : Category.values()) {
            Map<String, Integer> actualByFqcn = countByFqcn(all, category);
            Map<String, Integer> frozenByFqcn = readFreezeClassCounts(category);

            List<String> mismatches = classCountMismatches(actualByFqcn, frozenByFqcn);
            if (!mismatches.isEmpty()) {
                failure.append("【クラス単位の凍結件数ミスマッチ: ").append(category.description).append("】")
                        .append(System.lineSeparator())
                        .append(("『対処療法禁止・根治治療』原則により、"
                                + "docs/architecture/datetime_policy_utc_instant_vs_wallclock.md の方針"
                                + "（起きた瞬間はInstant/OffsetDateTime、壁時計はLocalDate/LocalTime+明示TZ）"
                                + "へ是正すること。台帳ファイル: %s"
                                + "（クラス単位の件数。メソッド名変更では変わらないため、それだけでは"
                                + "落ちない。本テストのJavadoc『凍結キーはクラス単位の件数』を参照）。%n")
                                .formatted(category.freezeFile))
                        .append(String.join(System.lineSeparator(), mismatches))
                        .append(System.lineSeparator()).append(System.lineSeparator());
            }
        }

        assertThat(failure.toString()).as(failure.toString()).isEmpty();
    }

    private static Map<String, Integer> countByFqcn(List<Violation> all, Category category) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Violation v : all) {
            if (v.category() == category) {
                counts.merge(v.fqcn(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * クラス単位の凍結件数比較の判定本体。ファイルI/Oを含まないため単体テスト可能
     * （{@link DateTimeAndZoneGuardScanningLogicTest} の {@code classCountMismatches_} 系）。
     * <ul>
     *   <li>実測件数 &gt; 台帳件数 → 新規違反（増加分だけ）として fail</li>
     *   <li>実測件数 &lt; 台帳件数 → 是正が進んだ台帳更新漏れとして fail（メッセージの趣旨が異なるだけ）</li>
     *   <li>台帳に無いクラスで実測がある → 新規クラスでの違反として fail</li>
     *   <li>実測に無いクラスが台帳に残っている → 陳腐化エントリとして fail（chip-away 対象）</li>
     * </ul>
     */
    static List<String> classCountMismatches(Map<String, Integer> actualByFqcn, Map<String, Integer> frozenByFqcn) {
        List<String> mismatches = new ArrayList<>();
        for (var e : actualByFqcn.entrySet()) {
            String fqcn = e.getKey();
            int actual = e.getValue();
            Integer frozen = frozenByFqcn.get(fqcn);
            if (frozen == null) {
                mismatches.add("新規クラスでの違反: %s に %d件（台帳未登録。新規追加は禁止）".formatted(fqcn, actual));
            } else if (actual > frozen) {
                mismatches.add("増加: %s は実測%d件 > 台帳%d件（新規違反%d件。禁止）"
                        .formatted(fqcn, actual, frozen, actual - frozen));
            } else if (actual < frozen) {
                mismatches.add(("減少: %s は実測%d件 < 台帳%d件（是正が進んだか、あるいは同一カテゴリの"
                        + "他クラスへ違反が移動した。いずれの場合も台帳を実測%d件へ更新すること）")
                        .formatted(fqcn, actual, frozen, actual));
            }
        }
        for (var e : frozenByFqcn.entrySet()) {
            if (!actualByFqcn.containsKey(e.getKey())) {
                mismatches.add("陳腐化: %s は台帳に%d件あるが実コードに1件も無い（根治済みなら台帳から削除。chip-away）"
                        .formatted(e.getKey(), e.getValue()));
            }
        }
        return mismatches;
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
            int total = readFreezeClassCounts(category).values().stream().mapToInt(Integer::intValue).sum();
            actualCounts.put(category, total);
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
    private static final int EXPECTED_FROZEN_NO_ARG_NOW = 1672;
    // 2026-08-13 返済 -28件（全件）: CMP-023 第1ロット。ZoneId.systemDefault() の28箇所を全て
    // UserZoneLocalDateTimeParser.SERVER_ZONE への明示参照へ置き換えた（挙動不変。同値変換）。
    private static final int EXPECTED_FROZEN_ZONE_SYSTEM_DEFAULT = 0;
    // 2026-08-13 返済 -1件: UserTimezoneFilter の ZoneId.of("Asia/Tokyo") 重複定義を
    // UserZoneLocalDateTimeParser.SERVER_ZONE 参照へ寄せた（issue #2616 / CMP-023 chip-away）。
    private static final int EXPECTED_FROZEN_ZONE_LITERAL = 52;
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
