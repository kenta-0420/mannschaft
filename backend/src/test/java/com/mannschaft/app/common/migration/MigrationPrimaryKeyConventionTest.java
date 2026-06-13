package com.mannschaft.app.common.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新規テーブルの主キー規約の番人テスト（D-2a・SQL 走査）:
 * <b>major バージョン 70 以降のマイグレーションが作成する {@code CREATE TABLE} の
 * 主キーに {@code AUTO_INCREMENT} を使ってはならない</b>。
 *
 * <p>設計指針: {@code CLAUDE.md}「DB 設計の原則 #6」— 新規テーブルの主キーは
 * UUIDv7（{@code BINARY(16)} / {@code CHAR(36)}）にすること。
 * BIGINT AUTO_INCREMENT は単一の発番サーバーが必要でシャーディングできない。
 *
 * <h2>このテストの方式（Docker 不要・Flyway 起動不要）</h2>
 * <p>{@code FlywayFromScratchMigrationTest} と異なり、本テストは実 DB を起動せず
 * {@code src/main/resources/db/migration} 配下の {@code .sql} を<b>ファイル内容として
 * 正規表現で静的検査</b>する。Docker / Testcontainers に依存しないため CI のどの
 * シャードでも安価に常時実行できる。
 *
 * <h2>判定ロジック</h2>
 * <ul>
 *   <li>ファイル名 {@code V<major>.<minor>__...sql} から major バージョンを抽出。</li>
 *   <li>{@code major >= 70} のファイルのみ検査対象（原則 #6 は 2026-05-11〜の
 *       新規テーブルに適用。それ以前の major は対象外）。</li>
 *   <li>{@code CREATE TABLE <name> (...)} 文を抽出し、本体に {@code AUTO_INCREMENT} を
 *       含む場合（＝主キーの AUTO_INCREMENT。MySQL の AUTO_INCREMENT は必ずキー列に
 *       付与される）は規約違反とする。</li>
 *   <li>ただし {@link #ALLOWLISTED_TABLES allowlist}（既知の許容済み逸脱）の
 *       テーブルは fail させない。</li>
 * </ul>
 *
 * <h2>allowlist の扱い</h2>
 * <p>allowlist 外で新たに AUTO_INCREMENT 主キーのテーブルが現れたら fail する。
 * allowlist は最小限に保ち、逸脱を追加する際は本テストに明記して棚卸し可能にする。
 */
@DisplayName("新規テーブル(major>=70)主キー UUIDv7 規約テスト（AUTO_INCREMENT 禁止）")
class MigrationPrimaryKeyConventionTest {

    /** 本規約を適用し始める major バージョン（原則 #6 導入時期相当）。 */
    private static final int CONVENTION_MIN_MAJOR = 70;

    /**
     * 既知の許容済み逸脱（allowlist）。これらは AUTO_INCREMENT 主キーでも fail させない。
     * <ul>
     *   <li>{@code csp_reports}（V71.014）— CSP 違反レポートの追記専用ログ表</li>
     *   <li>{@code schedule_media_uploads}（V75.001）</li>
     * </ul>
     */
    private static final Set<String> ALLOWLISTED_TABLES = Set.of(
        "csp_reports",
        "schedule_media_uploads");

    /** {@code V<major>.<minor>__name.sql} から major を取り出す。 */
    private static final Pattern VERSION_PATTERN =
        Pattern.compile("^V(\\d+)\\..*\\.sql$");

    /** {@code CREATE TABLE [IF NOT EXISTS] `?name`?} のテーブル名抽出。 */
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
        "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern AUTO_INCREMENT_PATTERN =
        Pattern.compile("AUTO_INCREMENT", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("major>=70 の CREATE TABLE 主キーに AUTO_INCREMENT が使われていない_allowlist 除く")
    void newTablesMustNotUseAutoIncrementPrimaryKey() {
        Path migrationDir = locateMigrationDir();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.list(migrationDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                .sorted()
                .forEach(p -> collectViolations(p, violations));
        } catch (IOException e) {
            throw new UncheckedIOException(
                "マイグレーションディレクトリの走査に失敗: " + migrationDir, e);
        }

        assertThat(violations)
            .as("major>=70 の新規テーブルで AUTO_INCREMENT 主キーを使っているものがある。"
                + "原則 #6 に従い UUIDv7 (BINARY(16)/CHAR(36)) を使うこと。"
                + "正当な逸脱（マスタ表・シングルトン表等）なら ALLOWLISTED_TABLES に"
                + "理由付きで追加すること。違反一覧:\n" + String.join("\n", violations))
            .isEmpty();
    }

    /**
     * 1 ファイルを検査し、major>=70 かつ allowlist 外の AUTO_INCREMENT 主キーがあれば
     * {@code violations} に追記する。
     */
    private static void collectViolations(Path sqlFile, List<String> violations) {
        String fileName = sqlFile.getFileName().toString();
        Integer major = extractMajor(fileName);
        if (major == null || major < CONVENTION_MIN_MAJOR) {
            return;
        }
        String content;
        try {
            content = Files.readString(sqlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("SQL 読み込み失敗: " + fileName, e);
        }

        // CREATE TABLE 文ごとにテーブル本体を切り出し、AUTO_INCREMENT の有無を判定。
        Matcher createMatcher = CREATE_TABLE_PATTERN.matcher(content);
        while (createMatcher.find()) {
            String tableName = createMatcher.group(1);
            int bodyStart = createMatcher.end();
            // 当該 CREATE TABLE の本体（次の CREATE TABLE か文末まで）を対象にする。
            int nextCreate = findNextCreateTable(content, bodyStart);
            String body = content.substring(bodyStart, nextCreate);

            if (AUTO_INCREMENT_PATTERN.matcher(body).find()
                    && !ALLOWLISTED_TABLES.contains(tableName)) {
                violations.add(String.format(
                    "%s: テーブル %s が AUTO_INCREMENT 主キーを使用（major=%d）",
                    fileName, tableName, major));
            }
        }
    }

    /** {@code from} 以降で次の {@code CREATE TABLE} が始まる位置（無ければ文末）。 */
    private static int findNextCreateTable(String content, int from) {
        Matcher m = CREATE_TABLE_PATTERN.matcher(content);
        if (m.find(from)) {
            return m.start();
        }
        return content.length();
    }

    /** ファイル名から major バージョンを抽出（取れなければ {@code null}）。 */
    private static Integer extractMajor(String fileName) {
        Matcher m = VERSION_PATTERN.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        return Integer.parseInt(m.group(1));
    }

    /**
     * マイグレーションディレクトリを特定する。
     *
     * <p>まず classpath の {@code db/migration}（テストクラスパスに含まれる）を試み、
     * 取得できない場合は gradle テストの作業ディレクトリ（{@code projectDir = backend/}）
     * 基準の {@code src/main/resources/db/migration} にフォールバックする。
     * これにより IDE 実行・gradle 実行のいずれでも解決できる。
     */
    private static Path locateMigrationDir() {
        try {
            var url = MigrationPrimaryKeyConventionTest.class.getClassLoader()
                .getResource("db/migration");
            if (url != null && "file".equals(url.getProtocol())) {
                Path p = Paths.get(url.toURI());
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
        } catch (Exception ignored) {
            // フォールバックへ
        }
        Path fallback = Paths.get("src", "main", "resources", "db", "migration");
        assertThat(Files.isDirectory(fallback))
            .as("マイグレーションディレクトリが見つからない: " + fallback.toAbsolutePath())
            .isTrue();
        return fallback;
    }
}
