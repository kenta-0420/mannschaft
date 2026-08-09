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

    /**
     * {@code CREATE [TEMPORARY] TABLE [IF NOT EXISTS] `?name`?} のテーブル名抽出。
     *
     * <p>group(1) が非 null なら {@code TEMPORARY}、group(2) がテーブル名。
     * {@code TEMPORARY} を<b>認識だけはする</b>のが要点で、認識しないと
     * 一時表の本体が直前の実テーブルの本体に紛れ込み、違反を別のテーブルのせいにしてしまう。</p>
     */
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
        "CREATE\\s+(TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?",
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
        String raw;
        try {
            raw = Files.readString(sqlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("SQL 読み込み失敗: " + fileName, e);
        }
        // コメントは DDL ではない。除去せずに走査すると、
        // 「AUTO_INCREMENT を使わない」と説明した注釈自体を違反として検出してしまう
        // （実際に V175 でこの誤検知が起きた）。
        String content = stripComments(raw);

        // CREATE TABLE 文ごとにテーブル本体を切り出し、AUTO_INCREMENT の有無を判定。
        Matcher createMatcher = CREATE_TABLE_PATTERN.matcher(content);
        while (createMatcher.find()) {
            boolean temporary = createMatcher.group(1) != null;
            String tableName = createMatcher.group(2);

            // 一時テーブルは対象外。
            // 原則 #6（新規テーブルは UuidV7Entity 継承＝主キー UUIDv7）は
            // ドメインの「永続表」に対する規約であり、マイグレーション実行中だけ存在して
            // セッション終了で消える作業用の一時表には Entity も主キー設計も存在しない。
            // 除外は番人を緩めるのではなく、対象範囲を規約の意図に合わせる修正である。
            // （履歴上、一時表を含むマイグレーションは V175 が唯一であり、
            //   従来 allowlist で黙らされていた一時表は存在しない＝既存の検出力は落ちない）
            if (temporary) {
                continue;
            }

            // 本体は「当該 CREATE TABLE 文の終端（;）まで」に限定する。
            // 以前は「次の CREATE TABLE まで」としていたため、
            // 認識できない CREATE TEMPORARY TABLE を挟むとその中身まで
            // 直前の実テーブルの本体に含まれ、違反を別のテーブルのせいにしていた。
            int bodyStart = createMatcher.end();
            String body = content.substring(bodyStart, findStatementEnd(content, bodyStart));

            if (AUTO_INCREMENT_PATTERN.matcher(body).find()
                    && !ALLOWLISTED_TABLES.contains(tableName)) {
                violations.add(String.format(
                    "%s: テーブル %s が AUTO_INCREMENT 主キーを使用（major=%d）",
                    fileName, tableName, major));
            }
        }
    }

    /**
     * SQL コメント（行コメントとブロックコメントの両方）を空白へ置換する。
     *
     * <p>削除ではなく同じ長さの空白に置き換えるのは、以降の位置計算
     * （本体の切り出し）がずれないようにするため。引用符の中の {@code --} は
     * コメントではないので、文字列リテラルは読み飛ばす。</p>
     */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sb, i, c);
            } else if (c == '-' && i + 1 < sb.length() && sb.charAt(i + 1) == '-') {
                while (i < sb.length() && sb.charAt(i) != '\n') {
                    sb.setCharAt(i++, ' ');
                }
            } else if (c == '/' && i + 1 < sb.length() && sb.charAt(i + 1) == '*') {
                int end = sb.indexOf("*/", i + 2);
                end = (end < 0) ? sb.length() : end + 2;
                for (int j = i; j < end; j++) {
                    if (sb.charAt(j) != '\n') {
                        sb.setCharAt(j, ' ');
                    }
                }
                i = end - 1;
            }
        }
        return sb.toString();
    }

    /** {@code from} の位置にある引用符に対応する閉じ引用符の位置を返す。 */
    private static int skipQuoted(CharSequence s, int from, char quote) {
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return s.length() - 1;
    }

    /** {@code from} 以降で文を終える {@code ;} の位置（引用符内は無視。無ければ文末）。 */
    private static int findStatementEnd(String content, int from) {
        for (int i = from; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(content, i, c);
            } else if (c == ';') {
                return i;
            }
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
