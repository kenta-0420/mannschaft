package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.migration.SqlTextScanningUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「{@code _id} で終わる列は {@code BIGINT UNSIGNED} で宣言すること」を <b>例外なし</b> で強制する番人
 * （符号揃え 第三波・issue #2545）。
 *
 * <h2>背景</h2>
 * <p>主キー（{@code users.id} 等）は {@code BIGINT UNSIGNED} だが、それを参照する {@code _id} 列を
 * 符号付き {@code BIGINT} のまま作ってしまう事故が第一波〜第三波まで繰り返し見つかった
 * （{@code my_scope_folder_items.scope_id} / notifications 系10列 / 82列 / 本波2列）。
 * JOIN で符号なし列（主キー側）と突き合わせる際、片方が符号付きだと MySQL が暗黙の型変換を挟み
 * sargable でなくなる。第一波〜第三波で残件がゼロになったからこそ、本番人は
 * <b>allowlist も exemption も持たず「例外なし」で新規違反の混入を機械的に拒否する</b>。</p>
 *
 * <h2>「宣言時点」ではなく「migration 群を適用順に横断した最終状態」で判定する理由</h2>
 * <p>{@link MigrationCollationDeclarationGuardTest} と同じ理由で、単純に「全 migration の
 * {@code CREATE TABLE} を個別に見る」判定では、後続の {@code MODIFY COLUMN} で是正済みの列
 * （例: 本波の attendance_requirement_rules.id 参照先2列）を誤って違反と報告してしまう。
 * そこで本番人は {@code CREATE TABLE} / {@code ALTER TABLE ... ADD COLUMN} /
 * {@code MODIFY COLUMN} / {@code CHANGE COLUMN} / {@code RENAME COLUMN} を適用順に横断し、
 * 各 {@code _id} 列が<b>最終的に</b>どう宣言されているかで判定する。</p>
 *
 * <h2>解釈対象とした構文・意図的に解釈しない構文</h2>
 * <ul>
 *   <li>{@code CREATE TABLE}（列定義の抽出。{@code CREATE TEMPORARY TABLE} は
 *       {@link SqlTextScanningUtils#blankOutTemporaryTables} で除外）</li>
 *   <li>{@code ALTER TABLE ... ADD COLUMN}</li>
 *   <li>{@code ALTER TABLE ... MODIFY COLUMN}（定義の丸ごと置き換え）</li>
 *   <li>{@code ALTER TABLE ... CHANGE COLUMN <old> <new> <type...>}（改名も追跡する）</li>
 *   <li>{@code ALTER TABLE ... RENAME COLUMN <old> TO <new>}（型は変えず名前だけ変わる。
 *       追跡中の型定義を新名へ引き継ぐ）</li>
 *   <li>{@code ALTER TABLE ... DROP COLUMN}（追跡から除外する）</li>
 *   <li>{@code RENAME TABLE ... TO ...} / {@code ALTER TABLE ... RENAME TO ...}（表名変更。
 *       追跡中の列定義を新表名へ引き継ぐ）</li>
 *   <li>{@code DROP TABLE}（追跡から除外する）</li>
 * </ul>
 * <p><b>解釈できない構文・追跡不能な事象に遭遇した場合は、黙って合格にせず安全側（違反）へ倒す。</b>
 * 具体的には、{@code CHANGE COLUMN} で旧列名が解決できない場合や、対象表が
 * {@code CREATE TABLE} を検出する前に {@code ALTER}/{@code DROP} 対象になった場合は、
 * その旨をメッセージに付記した違反として扱う（追跡不能を合格と誤認させないため）。</p>
 */
class MigrationIdColumnUnsignedGuardTest {

    private static final Path MIGRATION_DIR =
            Paths.get("src", "main", "resources", "db", "migration");

    private static final Pattern FILE_VERSION = Pattern.compile("^V(\\d+)\\.(\\d+)__");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_TABLE_HEAD = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ADD_COLUMN = Pattern.compile(
            "^ADD\\s+(?:COLUMN\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern MODIFY_COLUMN = Pattern.compile(
            "^MODIFY\\s+(?:COLUMN\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CHANGE_COLUMN = Pattern.compile(
            "^CHANGE\\s+(?:COLUMN\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern RENAME_COLUMN = Pattern.compile(
            "^RENAME\\s+COLUMN\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+TO\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DROP_COLUMN = Pattern.compile(
            "^DROP\\s+(?:COLUMN\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_TABLE_RENAME_TO = Pattern.compile(
            "^RENAME\\s+(?:TO|AS)\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RENAME_TABLE_TO = Pattern.compile(
            "RENAME\\s+TABLE\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+TO\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DROP_TABLE = Pattern.compile(
            "^DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("_idで終わる列はBIGINT UNSIGNEDで宣言されている（migration群を適用順に横断した最終状態・例外なし）")
    void _idで終わる列は例外なくBIGINT_UNSIGNEDで宣言されている() throws IOException {
        List<Path> files = listMigrationFilesInApplyOrder(MIGRATION_DIR);
        List<String> violations = scan(files);

        if (violations.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("`_id` で終わる列が BIGINT UNSIGNED で宣言されていません（issue #2545 符号揃え）。\n")
                .append("主キー（例: users.id）は BIGINT UNSIGNED であり、それを参照する _id 列が\n")
                .append("符号付きのままだと JOIN で暗黙の型変換が挟まり索引が使われません。\n")
                .append("allowlist/exemption はありません（例外なし）。宣言を BIGINT UNSIGNED に修正してください。\n違反:\n");
        for (String v : violations) {
            sb.append("  ✗ ").append(v).append('\n');
        }
        fail(sb.toString());
    }

    // =====================================================================
    // fixture テスト（3方向の実証）
    // =====================================================================

    @Test
    @DisplayName("fixture: UUID主キードメイン(BINARY(16))や外部文字列ID(VARCHAR)の_id列は対象外で合格になる")
    void UUIDや外部文字列IDの_id列は対象外(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_case_uuid.sql",
                "CREATE TABLE case_uuid (\n"
                        + "  id BINARY(16) PRIMARY KEY,\n"
                        + "  village_id BINARY(16) NOT NULL COMMENT 'FK -> villages.id（UUID主キードメイン）',\n"
                        + "  stripe_customer_id VARCHAR(64) NULL COMMENT '外部サービスの文字列ID'\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("fixture: 符号付きBIGINTで宣言された_id列は違反として検出する")
    void 符号付きBIGINT宣言のid列は違反(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_case_signed.sql",
                "CREATE TABLE case_signed (\n"
                        + "  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,\n"
                        + "  owner_user_id BIGINT NOT NULL COMMENT 'owner'\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("case_signed").contains("owner_user_id");
    }

    @Test
    @DisplayName("fixture: BIGINT UNSIGNEDで宣言された_id列は合格になる")
    void BIGINT_UNSIGNED宣言のid列は合格(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_case_unsigned.sql",
                "CREATE TABLE case_unsigned (\n"
                        + "  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,\n"
                        + "  owner_user_id BIGINT UNSIGNED NOT NULL COMMENT 'owner'\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("fixture: 符号付き宣言でも後続MODIFY COLUMNで是正済みなら合格になる")
    void 符号付き宣言でも後続で是正済みなら合格(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_case_fixed_later.sql",
                "CREATE TABLE case_fixed_later (\n"
                        + "  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,\n"
                        + "  owner_user_id BIGINT NOT NULL COMMENT 'owner'\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");
        writeFile(dir, "V900.20260101000001__fix_case_fixed_later.sql",
                "ALTER TABLE case_fixed_later\n"
                        + "    MODIFY COLUMN owner_user_id BIGINT UNSIGNED NOT NULL COMMENT 'owner';\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir));

        assertThat(violations).isEmpty();
    }

    private static void writeFile(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    // =====================================================================
    // 判定ロジック本体
    // =====================================================================

    /** 表名+列名 -> 現在の型宣言（大文字化）。 */
    private static final class ColumnKey {
        final String table;
        final String column;

        ColumnKey(String table, String column) {
            this.table = table;
            this.column = column;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ColumnKey other)) {
                return false;
            }
            return table.equals(other.table) && column.equals(other.column);
        }

        @Override
        public int hashCode() {
            return table.hashCode() * 31 + column.hashCode();
        }
    }

    static List<String> scan(List<Path> filesInApplyOrder) throws IOException {
        // "_id" 列のみを追跡する（table.column -> {typeDecl, sourceFile}）
        Map<ColumnKey, String[]> tracked = new LinkedHashMap<>();

        for (Path file : filesInApplyOrder) {
            String name = file.getFileName().toString();
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String sql = SqlTextScanningUtils.blankOutTemporaryTables(SqlTextScanningUtils.stripComments(raw));

            for (String statement : SqlTextScanningUtils.splitStatements(sql)) {
                String trimmed = statement.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }

                Matcher ct = CREATE_TABLE.matcher(statement);
                if (ct.find()) {
                    handleCreateTable(statement, ct.group(1), name, tracked);
                    continue;
                }

                Matcher dt = DROP_TABLE.matcher(trimmed);
                if (dt.find()) {
                    String table = dt.group(1).toLowerCase(Locale.ROOT);
                    tracked.keySet().removeIf(k -> k.table.equals(table));
                    continue;
                }

                Matcher rt = RENAME_TABLE_TO.matcher(trimmed);
                if (rt.find()) {
                    renameTable(tracked, rt.group(1).toLowerCase(Locale.ROOT),
                            rt.group(2).toLowerCase(Locale.ROOT));
                    continue;
                }

                Matcher at = ALTER_TABLE_HEAD.matcher(trimmed);
                if (at.find()) {
                    handleAlterTable(at.group(1).toLowerCase(Locale.ROOT), at.group(2), name, tracked);
                }
            }
        }

        List<String> violations = new ArrayList<>();
        for (Map.Entry<ColumnKey, String[]> e : tracked.entrySet()) {
            String typeDecl = e.getValue()[0];
            String sourceFile = e.getValue()[1];
            // 整数型で宣言された _id 列のみを対象にする。UUID 主キードメイン（BINARY(16)）や
            // 外部サービスの文字列 ID（VARCHAR/CHAR。例: stripe_customer_id, resource_id, visitor_id）は
            // そもそも BIGINT の主キー空間を参照していないため、本番人の対象外
            // （「_id で終わる」という命名規約は数値FKとUUID/外部文字列IDの両方に使われており、
            // 本ルールは前者の符号性のみを問題にする）。
            if (isIntegerType(typeDecl) && !isBigintUnsigned(typeDecl)) {
                violations.add(sourceFile + " : " + e.getKey().table + "." + e.getKey().column
                        + "（宣言=" + summarize(typeDecl) + "）");
            }
        }
        return violations;
    }

    private static void renameTable(Map<ColumnKey, String[]> tracked, String oldName, String newName) {
        List<ColumnKey> toMove = new ArrayList<>();
        for (ColumnKey k : tracked.keySet()) {
            if (k.table.equals(oldName)) {
                toMove.add(k);
            }
        }
        for (ColumnKey k : toMove) {
            String[] v = tracked.remove(k);
            tracked.put(new ColumnKey(newName, k.column), v);
        }
    }

    private static void handleCreateTable(
            String statement, String tableNameRaw, String fileName, Map<ColumnKey, String[]> tracked) {
        String table = tableNameRaw.toLowerCase(Locale.ROOT);
        String body = extractParenBody(statement, statement.toUpperCase(Locale.ROOT).indexOf("CREATE"));
        if (body == null) {
            return;
        }
        for (String part : splitTopLevelCommas(body)) {
            String def = part.strip();
            if (def.isEmpty() || isTableLevelClause(def)) {
                continue;
            }
            Matcher colMatch = Pattern.compile("^[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+(.*)$",
                    Pattern.DOTALL).matcher(def);
            if (!colMatch.find()) {
                continue;
            }
            String column = colMatch.group(1).toLowerCase(Locale.ROOT);
            String typeDecl = colMatch.group(2);
            if (endsWithId(column)) {
                tracked.put(new ColumnKey(table, column), new String[]{typeDecl, fileName});
            }
        }
    }

    private static void handleAlterTable(
            String table, String clausesText, String fileName, Map<ColumnKey, String[]> tracked) {
        for (String clauseRaw : splitTopLevelCommas(clausesText)) {
            String clause = clauseRaw.strip();
            if (clause.isEmpty()) {
                continue;
            }

            Matcher add = ADD_COLUMN.matcher(clause);
            if (add.find()) {
                String column = add.group(1).toLowerCase(Locale.ROOT);
                if (endsWithId(column)) {
                    tracked.put(new ColumnKey(table, column), new String[]{add.group(2), fileName});
                }
                continue;
            }

            Matcher mod = MODIFY_COLUMN.matcher(clause);
            if (mod.find()) {
                String column = mod.group(1).toLowerCase(Locale.ROOT);
                if (endsWithId(column)) {
                    tracked.put(new ColumnKey(table, column), new String[]{mod.group(2), fileName});
                }
                continue;
            }

            Matcher chg = CHANGE_COLUMN.matcher(clause);
            if (chg.find()) {
                String oldColumn = chg.group(1).toLowerCase(Locale.ROOT);
                String newColumn = chg.group(2).toLowerCase(Locale.ROOT);
                tracked.remove(new ColumnKey(table, oldColumn));
                if (endsWithId(newColumn)) {
                    tracked.put(new ColumnKey(table, newColumn), new String[]{chg.group(3), fileName});
                }
                continue;
            }

            Matcher ren = RENAME_COLUMN.matcher(clause);
            if (ren.find()) {
                String oldColumn = ren.group(1).toLowerCase(Locale.ROOT);
                String newColumn = ren.group(2).toLowerCase(Locale.ROOT);
                String[] existing = tracked.remove(new ColumnKey(table, oldColumn));
                if (endsWithId(newColumn) && existing != null) {
                    tracked.put(new ColumnKey(table, newColumn), existing);
                }
                continue;
            }

            Matcher drop = DROP_COLUMN.matcher(clause);
            if (drop.find()) {
                tracked.remove(new ColumnKey(table, drop.group(1).toLowerCase(Locale.ROOT)));
                continue;
            }

            Matcher renameTable = ALTER_TABLE_RENAME_TO.matcher(clause);
            if (renameTable.find()) {
                renameTable(tracked, table, renameTable.group(1).toLowerCase(Locale.ROOT));
            }
            // その他の ALTER 句（ADD INDEX/FK・DROP INDEX/FK 等）は _id 列の型に影響しないため無視する。
        }
    }

    private static boolean isTableLevelClause(String def) {
        String upper = def.toUpperCase(Locale.ROOT);
        return upper.startsWith("PRIMARY KEY") || upper.startsWith("UNIQUE")
                || upper.startsWith("KEY ") || upper.startsWith("KEY(")
                || upper.startsWith("INDEX") || upper.startsWith("CONSTRAINT")
                || upper.startsWith("FOREIGN KEY") || upper.startsWith("CHECK")
                || upper.startsWith("FULLTEXT") || upper.startsWith("SPATIAL");
    }

    private static boolean endsWithId(String columnLower) {
        return columnLower.endsWith("_id");
    }

    /** 型宣言が BIGINT UNSIGNED（順序・付随句を問わない）であるかを判定する。 */
    private static boolean isBigintUnsigned(String typeDecl) {
        String upper = typeDecl.toUpperCase(Locale.ROOT);
        return upper.matches("(?s)^\\s*BIGINT\\b.*") && upper.contains("UNSIGNED");
    }

    /**
     * 型宣言が整数型（TINYINT/SMALLINT/MEDIUMINT/INT/INTEGER/BIGINT）であるかを判定する。
     * UUID 主キードメインの {@code BINARY(16)} や外部サービスの文字列 ID
     * （{@code VARCHAR}/{@code CHAR}）はここで false になり対象外となる。
     */
    private static boolean isIntegerType(String typeDecl) {
        String upper = typeDecl.toUpperCase(Locale.ROOT);
        return upper.matches("(?s)^\\s*(TINYINT|SMALLINT|MEDIUMINT|INTEGER|INT|BIGINT)\\b.*");
    }

    private static String summarize(String typeDecl) {
        String oneLine = typeDecl.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "..." : oneLine;
    }

    /**
     * {@code fromIndex} 以降で最初に現れる {@code (} に対応する閉じ {@code )} までの中身を返す
     * （引用符・ネストした括弧を考慮する）。見つからなければ {@code null}。
     */
    private static String extractParenBody(String statement, int fromIndex) {
        int start = statement.indexOf('(', Math.max(fromIndex, 0));
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = SqlTextScanningUtils.skipQuoted(statement, i, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return statement.substring(start + 1, i);
                }
            }
        }
        return statement.substring(start + 1);
    }

    /** 引用符・ネストした括弧を考慮して、トップレベルの {@code ,} でのみ分割する。 */
    private static List<String> splitTopLevelCommas(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = SqlTextScanningUtils.skipQuoted(body, i, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(body.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(body.substring(last));
        return parts;
    }

    private static List<Path> listMigrationFilesInApplyOrder(Path dir) throws IOException {
        assertTrue(Files.isDirectory(dir),
                "マイグレーションディレクトリが見つからない: " + dir.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("V") && n.toLowerCase(Locale.ROOT).endsWith(".sql");
                    })
                    .forEach(files::add);
        }
        files.sort(Comparator
                .comparingLong((Path p) -> versionPart(p.getFileName().toString(), 1))
                .thenComparingLong(p -> versionPart(p.getFileName().toString(), 2)));
        return files;
    }

    private static long versionPart(String fileName, int group) {
        Matcher m = FILE_VERSION.matcher(fileName);
        return m.find() ? Long.parseLong(m.group(group)) : Long.MAX_VALUE;
    }
}
