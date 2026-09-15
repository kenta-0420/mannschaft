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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * 番人（CMP-260912-2258）: <b>Flyway migration の DML が「今」をセッション TZ 依存の関数で書く</b>
 * 書き方が新規に増えないようにする。
 *
 * <h2>なぜ migration も射程に入れるのか</h2>
 * <p>既存の番人 {@link RawSqlTimeColumnGuardTest} と {@link DateTimeAndZoneGuardTest} は
 * <b>{@code src/main/java} の {@code .java} だけ</b>を走査する。したがって同型の欠陥が
 * {@code db/migration/*.sql} 経由で入っても<b>一切検出されない</b>。実際、調査時点で
 * migration 側には {@code NOW()} 系の DML が 65 ファイル・746 箇所あり、{@code UTC_TIMESTAMP()} は
 * 0 箇所だった。この穴を塞ぐのが本番人の役割である。</p>
 *
 * <h2>今ずれているわけではない — それでも禁じる理由【必読】</h2>
 * <p>本戦役の調査で、{@code NOW()} が返す値は<b>全環境で {@code UTC_TIMESTAMP()} と同値</b>であることを
 * 実測で確認した。MySQL の {@code NOW()} は<b>セッションの {@code time_zone}</b> に従い、本プロジェクトは
 * 全環境でそれを UTC に固定しているためである（local: {@code docker-compose.yml} の
 * {@code --default-time-zone=+00:00} / CI: ランナー UTC の {@code SYSTEM} / test: Testcontainers の
 * {@code mysql:8.0} が {@code SYSTEM}=UTC / prod: RDS パラメータ {@code time_zone=UTC}）。
 * JDBC の {@code serverTimezone=UTC} はドライバ側の解釈を決めるだけでセッション TZ を書き換えない
 * （Connector/J の {@code forceConnectionTimeZoneToSession} は既定 false）。
 * 実測値: dev MySQL で {@code TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW()) = 0}。</p>
 *
 * <p>つまり既存 746 箇所は<b>ずれていない</b>。ただしその正しさは「セッション TZ が UTC である」という
 * <b>外部設定への依存</b>の上に乗っており、設定が 1 つ崩れた瞬間に 746 箇所が同時に 9 時間ずれる。
 * {@code UTC_TIMESTAMP()} は設定に依らず無条件に UTC 壁時計を返すため、この依存そのものが消える。
 * 生 SQL 側（{@link RawSqlTimeColumnGuardTest}）が既に {@code UTC_TIMESTAMP()} を正としている以上、
 * migration だけ別の流儀を許す理由は無い。そこで<b>既存は凍結・新規は禁止</b>とする。</p>
 *
 * <p>「セッション TZ が UTC である」という前提そのものは
 * {@code FlywayMigrationSessionTimeZoneUtcIT} が実 MySQL 接続で固定する（本番人の対になる番人）。</p>
 *
 * <h2>列 DEFAULT / ON UPDATE は対象外である</h2>
 * <p>{@code DEFAULT CURRENT_TIMESTAMP} / {@code ON UPDATE CURRENT_TIMESTAMP} / {@code DEFAULT NOW()} は
 * DDL のカラム定義であり、調査時点で 598 ファイル・1558 箇所に及ぶ。これらは
 * 「その列を省いた INSERT が来たときだけ」効く保険であり、実運用の INSERT は JPA 経路
 * （{@code @PrePersist}）か明示列指定のどちらかで必ず値を与えるため、発火機会自体が乏しい。
 * また MySQL の式 DEFAULT（{@code DEFAULT (UTC_TIMESTAMP())}）へ一括で倒すのは、
 * <b>適用済み migration の書き換え（＝ Flyway チェックサム不一致）</b>無しには実現できない。
 * したがって本番人は<b>DML の「今」だけ</b>を対象とし、DDL の列既定は
 * {@code FlywayMigrationSessionTimeZoneUtcIT} の担保に委ねる（射程の線引きは意図的である）。</p>
 *
 * <h2>凍結キーは migration ファイル単位の件数である</h2>
 * <p>{@link RawSqlTimeColumnGuardTest} と同じ思想で、行番号・出現順を凍結キーに含めない。
 * 適用済み migration はチェックサムにより不変なので、実際には件数も動かない。</p>
 *
 * @see RawSqlTimeColumnGuardTest
 * @see FlywayMigrationTimeFunctionGuardScanningLogicTest
 */
@DisplayName("番人: migration の DML がセッションTZ依存の「今」を新規に増やしていないこと（CMP-260912-2258）")
class FlywayMigrationTimeFunctionGuardTest {

    /** 凍結台帳（{@code <migration ファイル名>|<件数>}）。 */
    private static final Path FREEZE_FILE = Paths.get(
            "src", "test", "resources", "flyway_migration_time_guard", "session_tz_time_function_freeze.txt");

    /**
     * セッション TZ に従って「今」を返す関数。{@code UTC_TIMESTAMP()} は正解なので含めない。
     *
     * <p>量指定子はすべて上限付きにしてバックトラックを有界にする
     * （{@link RawSqlTimeColumnGuardTest} で実測した破滅的バックトラック対策と同じ理由）。</p>
     */
    static final Pattern SESSION_TZ_NOW = Pattern.compile(
            "(?i)\\b(?:NOW\\s{0,4}\\(\\s{0,4}\\d{0,2}\\s{0,4}\\)"
                    + "|SYSDATE\\s{0,4}\\(\\s{0,4}\\d{0,2}\\s{0,4}\\)"
                    + "|LOCALTIMESTAMP(?:\\s{0,4}\\(\\s{0,4}\\d{0,2}\\s{0,4}\\))?"
                    + "|CURRENT_TIMESTAMP(?:\\s{0,4}\\(\\s{0,4}\\d{0,2}\\s{0,4}\\))?)");

    /** 直前が列既定（{@code DEFAULT} / {@code ON UPDATE}）であることの判定。一致位置の直前だけを見る。 */
    static final Pattern COLUMN_DEFAULT_CONTEXT =
            Pattern.compile("(?i)(?:\\bDEFAULT|\\bON\\s{1,4}UPDATE)\\s{0,4}$");

    record Violation(String file, int line, String snippet) {
        String describe() {
            return "%s (L%d): %s".formatted(file, line, snippet);
        }
    }

    // ────────────────────────────────────────────────────────────
    // 走査本体（package-private: スキャンロジックテストから直接呼ぶ）
    // ────────────────────────────────────────────────────────────

    /**
     * 1 migration 分の走査。
     *
     * <p>SQL の行コメント（{@code --} / {@code #}）・ブロックコメントと、文字列リテラル
     * （{@code '...'} / {@code "..."}）の<b>中身</b>を空白へ潰してから照合する。潰さないと
     * {@code COMMENT '次回試行時刻 (enqueue 時=NOW())'} のような説明文が違反として数えられてしまう
     * （実在: {@code V68.001__create_email_outbox.sql}）。</p>
     */
    static List<Violation> collectViolationsInFile(String raw, String fileName) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.contains("now") && !lower.contains("sysdate")
                && !lower.contains("current_timestamp") && !lower.contains("localtimestamp")) {
            return List.of();
        }
        String scan = maskCommentsAndLiterals(raw);
        List<Violation> violations = new ArrayList<>();
        Matcher m = SESSION_TZ_NOW.matcher(scan);
        while (m.find()) {
            String preceding = scan.substring(Math.max(0, m.start() - 24), m.start());
            if (COLUMN_DEFAULT_CONTEXT.matcher(preceding).find()) {
                continue; // 列 DEFAULT / ON UPDATE は射程外（クラス Javadoc 参照）
            }
            violations.add(new Violation(fileName, lineNumber(raw, m.start()),
                    raw.substring(m.start(), m.end()).strip()));
        }
        return violations;
    }

    /**
     * SQL のコメントと文字列リテラルの中身を空白へ潰す（改行は保つので行番号は変わらない）。
     *
     * <p>MySQL の文字列リテラルは {@code ''} と {@code \'} の両方でクォートを escape できるため、
     * どちらも「リテラル継続」として扱う。</p>
     */
    static String maskCommentsAndLiterals(String raw) {
        char[] out = raw.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if ((c == '-' && i + 1 < n && out[i + 1] == '-') || c == '#') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else if (c == '\'' || c == '"') {
                char quote = c;
                i++; // 開きクォートはそのまま残す（位置合わせのため潰す必要がない）
                while (i < n) {
                    if (out[i] == '\\' && i + 1 < n) {
                        if (out[i] != '\n') {
                            out[i] = ' ';
                        }
                        i++;
                        if (i < n && out[i] != '\n') {
                            out[i] = ' ';
                        }
                        i++;
                        continue;
                    }
                    if (out[i] == quote) {
                        if (i + 1 < n && out[i + 1] == quote) { // '' = escape されたクォート
                            out[i++] = ' ';
                            out[i++] = ' ';
                            continue;
                        }
                        i++; // 閉じクォート
                        break;
                    }
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    // ────────────────────────────────────────────────────────────
    // テスト本体
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("migration ファイル単位の凍結件数から増えていない（新規 migration の NOW() は禁止）")
    void noNewSessionTzTimeFunctionInMigrations() throws IOException {
        Path root = migrationRoot();
        List<Violation> all = assertTimeout(Duration.ofSeconds(30), () -> collectViolations(root));

        assertThat(all)
                .as("migration から走査対象を1件も検出できなかった（走査パスの前提が壊れた可能性）: %s", root)
                .isNotEmpty();

        Map<String, Integer> actual = countByFile(all);
        Map<String, Integer> frozen = readFreezeCounts();
        List<String> mismatches = DateTimeAndZoneGuardTest.classCountMismatches(actual, frozen);

        assertThat(mismatches)
                .as("""
                    【migration の「今」がファイル単位の凍結件数から動いた】
                    『対処療法禁止・根治治療』原則により、新しい migration で時刻列へ「今」を書くときは
                    NOW() / CURRENT_TIMESTAMP / SYSDATE ではなく UTC_TIMESTAMP() を使うこと
                    （docs/architecture/datetime_policy_utc_instant_vs_wallclock.md 4.2節）。
                    凍結台帳へ追記して黙らせてはならない。台帳ファイル: %s
                    差分: %s""".formatted(FREEZE_FILE, mismatches))
                .isEmpty();
    }

    @Test
    @DisplayName("凍結件数が記録済みスナップショットと一致している（増減とも検知）")
    void freezeCountsMatchRecordedSnapshot() throws IOException {
        Map<String, Integer> frozen = readFreezeCounts();
        int files = frozen.size();
        int total = frozen.values().stream().mapToInt(Integer::intValue).sum();

        assertThat(total)
                .as("凍結総件数が EXPECTED_FROZEN_TOTAL と一致しない（実=%d, 記録=%d）。"
                                + "増えた場合は新規の凍結追記であり禁止。減った場合は是正の反映なので定数を更新すること。",
                        total, EXPECTED_FROZEN_TOTAL)
                .isEqualTo(EXPECTED_FROZEN_TOTAL);
        assertThat(files)
                .as("凍結ファイル数が EXPECTED_FROZEN_FILES と一致しない（実=%d, 記録=%d）", files, EXPECTED_FROZEN_FILES)
                .isEqualTo(EXPECTED_FROZEN_FILES);
    }

    /** 返済台帳のスナップショット（CMP-260912-2258 実装時点）。減ったら追随更新し、増やしてはならない。 */
    static final int EXPECTED_FROZEN_TOTAL = 746;
    static final int EXPECTED_FROZEN_FILES = 65;

    // ────────────────────────────────────────────────────────────
    // 走査・凍結ファイル入出力
    // ────────────────────────────────────────────────────────────

    static List<Violation> collectViolations(Path root) {
        List<Violation> violations = new ArrayList<>();
        for (Path file : sqlFiles(root)) {
            violations.addAll(collectViolationsInFile(read(file), relativeName(root, file)));
        }
        return violations;
    }

    private static Map<String, Integer> countByFile(List<Violation> all) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Violation v : all) {
            counts.merge(v.file(), 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> readFreezeCounts() throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Path path = resolve(FREEZE_FILE);
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("凍結ファイルの行形式が不正: " + path + " の行 \"" + trimmed
                        + "\"。期待形式: <migrationファイル名>|<件数>");
            }
            counts.merge(parts[0], Integer.parseInt(parts[1].strip()), Integer::sum);
        }
        return counts;
    }

    private static Path migrationRoot() {
        for (String candidate : new String[]{
                "src/main/resources/db/migration", "backend/src/main/resources/db/migration"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("db/migration が見つからない（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    private static Path resolve(Path relative) {
        for (Path candidate : new Path[]{relative, Paths.get("backend").resolve(relative)}) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "凍結リストが見つからない: " + relative + "（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
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

    private static List<Path> sqlFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".sql")).sorted().collect(Collectors.toList());
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
