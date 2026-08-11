package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 * での壁時計の保持）へ是正すること。凍結件数が<b>増えたら</b>本テストは fail する
 * （{@link #noStaleOrGrowingFreezeEntries} 系）。件数が<b>減った</b>場合は凍結リストから
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
    /** フィールド／DTOプロパティ宣言候補。ローカル変数と区別する処理は {@link #isFieldLevel} 側。 */
    static final Pattern LOCAL_DATE_TIME_DECL =
            Pattern.compile("\\bLocalDateTime\\s+([A-Za-z_$][\\w$]*)\\s*[;=,)]");

    /** メソッド宣言（シグネチャの開始位置から本体閉じ括弧まで。引数もメソッド内側として扱う）。 */
    static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?[ \\t]*\\r?\\n[ \\t]*)*"
                    + "(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?"
                    + "(?:<[^>]*>\\s+)?(?:[\\w.<>\\[\\],? ]+?)\\s+(\\w+)\\s*\\([^;{]*\\)\\s*"
                    + "(?:throws\\s+[\\w.,\\s]+)?\\{");

    /** コンストラクタ宣言（戻り値型を持たない点だけがメソッドと異なる）。 */
    static final Pattern CTOR_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?[ \\t]*\\r?\\n[ \\t]*)*"
                    + "(?:public|private|protected)\\s+([A-Z]\\w*)\\s*\\([^;{]*\\)\\s*"
                    + "(?:throws\\s+[\\w.,\\s]+)?\\{");

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
        List<Violation> all = collectViolations(root);

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
                failure.append("【新規違反: ").append(category.description).append("】%n".formatted())
                        .append("『対処療法禁止・根治治療』原則により、"
                                + "docs/architecture/datetime_policy_utc_instant_vs_wallclock.md の方針"
                                + "（起きた瞬間はInstant/OffsetDateTime、壁時計はLocalDate/LocalTime+明示TZ）"
                                + "へ是正すること。既存の負債として凍結する場合は %s に追記すること"
                                + "（原則非推奨。本テストのJavadoc『凍結リストの位置づけ』を参照。"
                                + "CMP-023 で返済する借金の台帳であり免罪符ではない）。%n"
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
     * 凍結件数のスナップショット（借金の台帳）。値を減らす方向の変更（是正の反映）は歓迎するが、
     * <b>増やす方向の変更はこのテスト自体が fail する</b>（{@link #noNewOldStyleDateTimeUsage} が
     * 凍結ファイルへの新規追記を止められない唯一の抜け道が「凍結ファイルへ直接追記すること」自体
     * であるため、件数の上限をここに固定して二重に縛る）。
     */
    @Test
    @DisplayName("凍結件数（CMP-023返済台帳）が現在の上限を超えて増えていない")
    void freezeCountsDoNotExceedRecordedCeiling() throws IOException {
        Map<Category, Integer> ceilings = Map.of(
                Category.NO_ARG_NOW, EXPECTED_FROZEN_NO_ARG_NOW,
                Category.ZONE_SYSTEM_DEFAULT, EXPECTED_FROZEN_ZONE_SYSTEM_DEFAULT,
                Category.ZONE_LITERAL, EXPECTED_FROZEN_ZONE_LITERAL,
                Category.LOCAL_DATE_TIME_FIELD, EXPECTED_FROZEN_LOCAL_DATE_TIME_FIELD);

        StringBuilder failure = new StringBuilder();
        for (Category category : Category.values()) {
            int actual = readFreezeList(category.freezeFile).size();
            int ceiling = ceilings.get(category);
            if (actual > ceiling) {
                failure.append(("凍結件数が増加した: %s は %d 件（記録上限 %d 件）。"
                        + "新規の凍結追記は禁止（CMP-023返済台帳）。是正するか、"
                        + "本当に必要な場合のみ本テストの EXPECTED_FROZEN_* 定数と"
                        + "javadoc上の説明を更新すること。%n")
                        .formatted(category.description, actual, ceiling));
            }
        }
        assertThat(failure.toString()).as(failure.toString()).isEmpty();
    }

    /**
     * CMP-023 返済台帳。凍結ファイルの実件数の上限値（現時点のスナップショット）。
     * 認可監査戦役の {@code EXPECTED_LINES_*} 方式を踏襲する。
     * 減った場合はここも追随して更新し、返済の進捗を数値で残すこと。
     */
    private static final int EXPECTED_FROZEN_NO_ARG_NOW = 2246;
    private static final int EXPECTED_FROZEN_ZONE_SYSTEM_DEFAULT = 30;
    private static final int EXPECTED_FROZEN_ZONE_LITERAL = 66;
    private static final int EXPECTED_FROZEN_LOCAL_DATE_TIME_FIELD = 3924;

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

    /** 1ファイル分の走査。スキャンロジックの回帰テストから合成ソースに対しても直接呼べる。 */
    static List<Violation> collectViolationsInFile(String raw, String fqcn) {
        List<Violation> violations = new ArrayList<>();

        // コメント・文字列/テキストブロックの中身を消した版（now()/systemDefault()/フィールド宣言用）。
        String maskedLiterals = JavaSourceScanningUtils.maskCommentsAndLiterals(raw);
        // コメントのみ消し、文字列は残す版（ZoneId.of("Asia/Tokyo") のリテラル値を読む用）。
        String maskedComments = JavaSourceScanningUtils.maskCommentsOnly(raw);

        List<MethodSpan> methods = findMethodSpans(maskedLiterals);

        addCallViolations(violations, maskedLiterals, NO_ARG_NOW, Category.NO_ARG_NOW, fqcn, methods, raw);
        addCallViolations(violations, maskedLiterals, ZONE_SYSTEM_DEFAULT, Category.ZONE_SYSTEM_DEFAULT, fqcn, methods, raw);
        addCallViolations(violations, maskedComments, ZONE_LITERAL, Category.ZONE_LITERAL, fqcn, methods, raw);
        addFieldViolations(violations, maskedLiterals, fqcn, methods, raw);

        return violations;
    }

    private static void addCallViolations(List<Violation> violations, String scanText, Pattern pattern,
            Category category, String fqcn, List<MethodSpan> methods, String raw) {
        Matcher m = pattern.matcher(scanText);
        Map<String, Integer> seqByLocation = new HashMap<>();
        while (m.find()) {
            String location = enclosingMethodName(methods, m.start());
            int seq = seqByLocation.merge(location, 1, Integer::sum);
            int line = lineNumber(raw, m.start());
            violations.add(new Violation(category, fqcn, location, seq, line, snippet(raw, m.start(), m.end())));
        }
    }

    private static void addFieldViolations(List<Violation> violations, String scanText, String fqcn,
            List<MethodSpan> methods, String raw) {
        Matcher m = LOCAL_DATE_TIME_DECL.matcher(scanText);
        int seq = 0;
        while (m.find()) {
            if (isInsideAnyMethod(methods, m.start())) {
                continue; // ローカル変数／メソッド引数はフィールドではない。
            }
            seq++;
            int line = lineNumber(raw, m.start());
            violations.add(new Violation(Category.LOCAL_DATE_TIME_FIELD, fqcn, "(class-level)", seq, line,
                    snippet(raw, m.start(), m.end())));
        }
    }

    private static boolean isInsideAnyMethod(List<MethodSpan> methods, int offset) {
        for (MethodSpan s : methods) {
            if (s.start <= offset && offset < s.end) {
                return true;
            }
        }
        return false;
    }

    private record MethodSpan(String name, int start, int end) {
    }

    /** シグネチャ開始位置～本体閉じ括弧までを1スパンとする（引数もメソッド内側として扱う）。 */
    private static List<MethodSpan> findMethodSpans(String code) {
        List<MethodSpan> spans = new ArrayList<>();
        addSpansFor(spans, METHOD_DECL, code);
        addSpansFor(spans, CTOR_DECL, code);
        return spans;
    }

    private static void addSpansFor(List<MethodSpan> spans, Pattern declPattern, String code) {
        Matcher m = declPattern.matcher(code);
        while (m.find()) {
            int braceStart = m.end() - 1; // '{' の位置
            int braceEnd = findMatchingBrace(code, braceStart);
            if (braceEnd < 0) {
                continue;
            }
            spans.add(new MethodSpan(m.group(1), m.start(), braceEnd + 1));
        }
    }

    private static String enclosingMethodName(List<MethodSpan> methods, int offset) {
        MethodSpan best = null;
        for (MethodSpan s : methods) {
            if (s.start <= offset && offset < s.end) {
                if (best == null || (s.end - s.start) < (best.end - best.start)) {
                    best = s;
                }
            }
        }
        return best != null ? best.name : "(不明メソッド)";
    }

    private static int findMatchingBrace(String s, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < s.length(); i++) {
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
