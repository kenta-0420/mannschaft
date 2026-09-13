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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * 番人（CMP-260909-1446）: <b>JPA を迂回する生 SQL が時刻列の格納基準を割る</b>書き方が
 * 新規に増えないようにする。
 *
 * <h2>何を禁じるのか</h2>
 * <p>本アプリの DB 格納基準は {@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC} により
 * <b>UTC 壁時計</b>である。JPA 経路は {@code @PrePersist} の {@code LocalDateTime.now()}（JST 壁時計）を
 * Hibernate が UTC へ変換して格納する。ところが {@code JdbcTemplate} / {@code nativeQuery} は
 * この変換を通らないため、次の 3 つはいずれも<b>9 時間ずれる</b>。</p>
 * <ol>
 *   <li>{@code NOW()} / {@code CURRENT_TIMESTAMP} / {@code SYSDATE()} を SQL に書く
 *       （接続セッションのタイムゾーン依存。JPA 経路と基準が食い違う）</li>
 *   <li>時刻列（{@code *_at}）をプレースホルダで Java の壁時計と比較・代入する
 *       （{@code created_at &lt; ?} / {@code served_at = :now}）</li>
 *   <li>生 SQL を発行するクラスがエンティティの時刻ゲッター（{@code getCreatedAt()} 等）を読み、
 *       その値をバインド値として渡す</li>
 * </ol>
 *
 * <p><b>正解</b>は「Java 側で壁時計を作って束縛しない」こと、すなわち SQL リテラル
 * {@code UTC_TIMESTAMP()}（タイムゾーン設定に依らず UTC 壁時計を返す）を使うことである。
 * 前例と根拠は {@code AnnouncementReadStatusRepository#markAllAsReadByFeedIds} の Javadoc、
 * および {@code NotificationBulkFanoutService} の {@code created_at} 充填を参照。
 * 「Java 側で {@code LocalDateTime.now(ZoneOffset.UTC)} を渡す」案を採らないのは、
 * それが本番のコードから機械的に見分けられず、本番人で検出できなくなるためである。</p>
 *
 * <h2>ArchUnit ではなくソース走査型である理由</h2>
 * <p>「SQL 文字列リテラルの中身がどう書かれているか」は、文字列連結・テキストブロック・定数分割を
 * 跨いだ原文の姿としてはバイトコードから復元できない。{@link DateTimeAndZoneGuardTest} と同じく
 * ソース走査型とし、ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）は一切使わない
 * （{@code --tests} 絞り込み実行で ArchUnit 側の台帳を巻き込まない）。</p>
 *
 * <h2>凍結キーはクラス単位の件数である【必読】</h2>
 * <p>{@link DateTimeAndZoneGuardTest} と同型で、判定ロジックも
 * {@link DateTimeAndZoneGuardTest#classCountMismatches} を共有する。凍結キーは
 * {@code <カテゴリ>|<FQCN>|<件数>} であり、メソッド名・出現順を含めない。メソッド名の変更や
 * メソッド移動で既存負債が「新規違反」に化けて CI を止める事故（PR #2725）を構造的に避けるためである。</p>
 *
 * <h2>凍結台帳は返済すべき借金である</h2>
 * <p>台帳に載っているのは<b>是正されていない既存箇所</b>であり、別戦役で {@code UTC_TIMESTAMP()} 方式へ
 * 順次是正していく対象である。新規追記は禁止する。とりわけ次の 4 クラスは CMP-260909-1446 の横断調査で
 * 見つかった同型欠陥であり、別戦役として {@code docs/task-list.md} に起票済みである（本戦役の射程外）:</p>
 * <ul>
 *   <li>{@code com.mannschaft.app.chat.service.ChatMessageArchiveBatchService}</li>
 *   <li>{@code com.mannschaft.app.auth.service.AuditLogArchiveBatchService}</li>
 *   <li>{@code com.mannschaft.app.weather.service.GeonamesImportService}</li>
 * </ul>
 *
 * @see DateTimeAndZoneGuardTest
 * @see RawSqlTimeColumnGuardScanningLogicTest
 */
@DisplayName("番人: 生SQLが時刻列の格納基準を割る書き方が新規に増えていないこと（CMP-260909-1446）")
class RawSqlTimeColumnGuardTest {

    // ────────────────────────────────────────────────────────────
    // 検出パターン（package-private でスキャンロジックテストから再利用）
    // ────────────────────────────────────────────────────────────

    /** DB サーバのセッションTZ依存で「今」を得る関数。{@code UTC_TIMESTAMP()} は正解なので含めない。 */
    static final Pattern SQL_LOCAL_TIME_FUNCTION =
            Pattern.compile("(?i)\\b(?:NOW\\s*\\(\\s*\\)|CURRENT_TIMESTAMP\\b|SYSDATE\\s*\\(\\s*\\))");
    /**
     * 時刻列（{@code *_at}）を JDBC プレースホルダ／名前付きパラメータと結ぶ比較・代入。
     *
     * <p><b>先頭に {@code [a-z0-9_]*} 相当の量指定子を置いてはならない</b>。列名の手前に語構成文字が
     * 何文字でも並びうる形にすると、一致しない位置ごとに後方へ舐め直す<b>破滅的バックトラック</b>が起き、
     * 全 production ソース走査が 30 秒の上限を超えて JVM ごと落ちる（本テスト初版で実測）。
     * 代わりに {@code _at} を起点に固定長で照合し、「直前が語構成文字である（＝列名の一部）」ことは
     * {@link #precededByWordChar} で線形に確かめる。</p>
     */
    static final Pattern TIME_COLUMN_JAVA_BOUND =
            Pattern.compile("(?i)_at\\s{0,4}(?:<=|>=|<>|!=|=|<|>)\\s{0,4}[?:]");
    /**
     * エンティティの時刻ゲッター（{@code getCreatedAt()} / {@code getServedAt()} 等）。
     * 量指定子は上限付き（{@code {0,40}}）にしてバックトラックを有界にする（上記の理由と同じ）。
     */
    static final Pattern ENTITY_TIME_GETTER =
            Pattern.compile("\\bget[A-Z][A-Za-z0-9]{0,40}At\\s{0,4}\\(\\s{0,4}\\)");
    /** 「このクラスは生 SQL を発行する」ことの目印。 */
    static final Pattern RAW_SQL_MARKER =
            Pattern.compile("\\b(?:JdbcTemplate|jdbcTemplate|nativeQuery)\\b");

    enum Category {
        SQL_LOCAL_TIME_FUNCTION_IN_SQL(
                "生SQL中の NOW()/CURRENT_TIMESTAMP/SYSDATE（セッションTZ依存）",
                Paths.get("src", "test", "resources", "raw_sql_time_guard", "sql_local_time_function_freeze.txt")),
        TIME_COLUMN_JAVA_BOUND(
                "生SQLの時刻列(*_at)へ Java 側の値をプレースホルダで束縛",
                Paths.get("src", "test", "resources", "raw_sql_time_guard", "time_column_java_bound_freeze.txt")),
        RAW_SQL_ENTITY_TIME_GETTER(
                "生SQLを発行するクラスがエンティティの時刻ゲッターを読む（バインド値化）",
                Paths.get("src", "test", "resources", "raw_sql_time_guard", "raw_sql_entity_time_getter_freeze.txt"));

        final String description;
        final Path freezeFile;

        Category(String description, Path freezeFile) {
            this.description = description;
            this.freezeFile = freezeFile;
        }
    }

    record Violation(Category category, String fqcn, int line, String snippet) {
        String describe() {
            return "%s (L%d): %s".formatted(fqcn, line, snippet);
        }
    }

    // ────────────────────────────────────────────────────────────
    // 走査本体（package-private: スキャンロジックテストから直接呼ぶ）
    // ────────────────────────────────────────────────────────────

    /**
     * 1 ファイル分の走査。
     *
     * <p>コメント（Javadoc 含む）の中身は常に走査対象から外す。SQL 由来の 2 カテゴリは
     * <b>文字列リテラル／テキストブロックの内側だけ</b>を見る（Java 識別子に紛れた記述や
     * コメント中の SQL 例を誤検出しないため）。{@link Category#RAW_SQL_ENTITY_TIME_GETTER} は
     * Java コードそのものが対象なので、リテラルの<b>外側</b>だけを見る。</p>
     */
    static List<Violation> collectViolationsInFile(String raw, String fqcn) {
        if (!mayContainAnyTrigger(raw)) {
            // 走査の手がかりが 1 つも無いファイルはマスク処理そのものを省く。全 production ソース
            // （数千ファイル）に 2 回のマスク走査を掛けると 30 秒の上限を超えるため（初版で実測）。
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();

        // コメントのみ潰した版（文字列の中身は残る）と、コメント＋文字列の中身も潰した版。
        String maskedComments = JavaSourceScanningUtils.maskCommentsOnly(raw);
        String maskedLiterals = JavaSourceScanningUtils.maskCommentsAndLiterals(raw);

        addViolations(violations, maskedComments, SQL_LOCAL_TIME_FUNCTION,
                Category.SQL_LOCAL_TIME_FUNCTION_IN_SQL, fqcn, raw, maskedLiterals, true, false);
        addViolations(violations, maskedComments, TIME_COLUMN_JAVA_BOUND,
                Category.TIME_COLUMN_JAVA_BOUND, fqcn, raw, maskedLiterals, true, true);

        if (RAW_SQL_MARKER.matcher(maskedLiterals).find()) {
            addViolations(violations, maskedLiterals, ENTITY_TIME_GETTER,
                    Category.RAW_SQL_ENTITY_TIME_GETTER, fqcn, raw, maskedLiterals, false, false);
        }
        return violations;
    }

    /**
     * @param insideLiteralOnly    true なら「文字列リテラルの内側で始まる一致」だけを採る。
     *                             false なら scanText（リテラル潰し済み）をそのまま使う。
     * @param requirePrecedingWord true なら一致の直前が語構成文字であることを要求する
     *                             （{@code _at} 起点の照合で「列名の一部である」ことを線形に確かめる）。
     */
    private static void addViolations(List<Violation> out, String scanText, Pattern pattern,
                                      Category category, String fqcn, String raw,
                                      String maskedLiterals, boolean insideLiteralOnly,
                                      boolean requirePrecedingWord) {
        Matcher m = pattern.matcher(scanText);
        while (m.find()) {
            int start = m.start();
            if (requirePrecedingWord && !precededByWordChar(scanText, start)) {
                continue;
            }
            if (insideLiteralOnly && !isInsideLiteral(maskedLiterals, scanText, start)) {
                continue;
            }
            out.add(new Violation(category, fqcn, lineNumber(raw, start), snippet(raw, start, m.end())));
        }
    }

    /**
     * 走査に値するファイルかを、正規表現もマスク処理も使わない部分文字列一致だけで粗く判定する。
     *
     * <p><b>偽陰性を作らないこと</b>が唯一の要件である。ここで拾う語は、3 カテゴリの検出パターンが
     * 一致しうる文字列の<b>必要条件</b>（部分文字列）だけを並べてあり、これらを 1 つも含まないファイルには
     * 定義上どのパターンも一致しない。{@link RawSqlTimeColumnGuardScanningLogicTest} の各検出ケースが
     * この事前フィルタ込みで通ることで、条件が緩いままであることを担保する。</p>
     */
    static boolean mayContainAnyTrigger(String raw) {
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("_at")                    // TIME_COLUMN_JAVA_BOUND の列名
                || lower.contains("now")                // NOW()（空白の入り方に依らず必ず含む）
                || lower.contains("current_timestamp")
                || lower.contains("sysdate")
                || lower.contains("jdbctemplate")       // RAW_SQL_ENTITY_TIME_GETTER は
                || lower.contains("nativequery");       // 生SQLクラスでしか検出しない
    }

    /** 一致開始位置の直前が語構成文字（列名の一部）であるか。破滅的バックトラックを避けるための線形判定。 */
    static boolean precededByWordChar(String text, int offset) {
        if (offset <= 0) {
            return false;
        }
        char c = text.charAt(offset - 1);
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * オフセットが文字列リテラル／テキストブロックの内側かを判定する。
     *
     * <p>{@link JavaSourceScanningUtils#maskCommentsAndLiterals} はリテラルの<b>中身だけ</b>を
     * 空白へ潰し、{@link JavaSourceScanningUtils#maskCommentsOnly} はリテラルを残す。
     * したがって「コメント潰し版では非空白なのに、リテラル潰し版では空白」の位置は
     * リテラルの内側だと一意に決まる（本メソッドへ渡す一致開始位置は必ず非空白文字である）。</p>
     */
    static boolean isInsideLiteral(String maskedLiterals, String maskedComments, int offset) {
        if (offset < 0 || offset >= maskedLiterals.length()) {
            return false;
        }
        return maskedLiterals.charAt(offset) == ' ' && maskedComments.charAt(offset) != ' ';
    }

    // ────────────────────────────────────────────────────────────
    // テスト本体
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("生SQLの時刻列まわりの違反がクラス単位の凍結件数から増えていない")
    void noNewRawSqlTimeColumnViolations() throws IOException {
        Path root = sourceRoot();
        List<Violation> all = assertTimeout(Duration.ofSeconds(30), () -> collectViolations(root));

        assertThat(all)
                .as("production コードから走査対象を1件も検出できなかった（走査パスの前提が壊れた可能性）")
                .isNotEmpty();

        StringBuilder failure = new StringBuilder();
        for (Category category : Category.values()) {
            Map<String, Integer> actual = countByFqcn(all, category);
            Map<String, Integer> frozen = readFreezeClassCounts(category);
            List<String> mismatches = DateTimeAndZoneGuardTest.classCountMismatches(actual, frozen);
            if (!mismatches.isEmpty()) {
                failure.append("【クラス単位の凍結件数ミスマッチ: ").append(category.description).append("】")
                        .append(System.lineSeparator())
                        .append(("『対処療法禁止・根治治療』原則により、JPA を迂回する書き込み／読み出しでは"
                                + "時刻列を Java 側から束縛せず SQL の UTC_TIMESTAMP() を使うこと"
                                + "（docs/architecture/datetime_policy_utc_instant_vs_wallclock.md）。"
                                + "台帳ファイル: %s%n").formatted(category.freezeFile))
                        .append(String.join(System.lineSeparator(), mismatches))
                        .append(System.lineSeparator()).append(System.lineSeparator());
            }
        }
        assertThat(failure.toString()).as(failure.toString()).isEmpty();
    }

    @Test
    @DisplayName("凍結件数が記録済みスナップショットと一致している（増減とも検知）")
    void freezeCountsMatchRecordedSnapshot() throws IOException {
        Map<Category, Integer> actual = new HashMap<>();
        for (Category category : Category.values()) {
            actual.put(category,
                    readFreezeClassCounts(category).values().stream().mapToInt(Integer::intValue).sum());
        }
        Map<Category, Integer> expected = Map.of(
                Category.SQL_LOCAL_TIME_FUNCTION_IN_SQL, EXPECTED_FROZEN_SQL_LOCAL_TIME_FUNCTION,
                Category.TIME_COLUMN_JAVA_BOUND, EXPECTED_FROZEN_TIME_COLUMN_JAVA_BOUND,
                Category.RAW_SQL_ENTITY_TIME_GETTER, EXPECTED_FROZEN_RAW_SQL_ENTITY_TIME_GETTER);

        List<String> mismatches = new ArrayList<>();
        for (Category category : Category.values()) {
            int a = actual.get(category);
            int s = expected.get(category);
            if (a != s) {
                mismatches.add("%s: 実件数=%d, 記録スナップショット=%d (%s)".formatted(
                        category.name(), a, s, a > s ? "増加=禁止" : "減少=要追随更新"));
            }
        }
        assertThat(mismatches)
                .as("凍結件数が EXPECTED_FROZEN_* と一致しない。増えた場合は新規の凍結追記であり禁止。"
                        + "減った場合は是正の反映なので定数を新しい実件数へ更新すること。差分: %s".formatted(mismatches))
                .isEmpty();
    }

    /** 返済台帳のスナップショット（CMP-260909-1446 実装時点）。減ったら追随更新し、増やしてはならない。 */
    private static final int EXPECTED_FROZEN_SQL_LOCAL_TIME_FUNCTION = 36;
    private static final int EXPECTED_FROZEN_TIME_COLUMN_JAVA_BOUND = 54;
    private static final int EXPECTED_FROZEN_RAW_SQL_ENTITY_TIME_GETTER = 4;

    // ────────────────────────────────────────────────────────────
    // 走査・凍結ファイル入出力
    // ────────────────────────────────────────────────────────────

    static List<Violation> collectViolations(Path root) {
        List<Violation> violations = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            violations.addAll(collectViolationsInFile(read(file), toFqcn(root, file)));
        }
        return violations;
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
