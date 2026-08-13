package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.migration.SqlTextScanningUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

/**
 * migration の最終状態（NULL 許容・NOT NULL）と Entity 側 {@code @Column(nullable=...)} /
 * {@code @JoinColumn(nullable=...)} の食い違いを実測する<b>探索的な報告テスト</b>
 * （符号揃え 第三波・issue #2545 で見つかった同型の盲点、マスター承認済みの新規要件）。
 *
 * <h2>なぜこの検出が必要か</h2>
 * <p>Entity 側で {@code nullable = false} を書き忘れると、テストは Entity 由来のスキーマ
 * （{@code ddl-auto=create}）で走るため <b>必須列の詰め忘れが全 IT をすり抜け、実機でのみ 500</b> になる
 * （CMP-021 の実例）。符号性の盲点とまったく同じ構造 —— 「本番 DDL の実際の制約」と
 * 「テストが使う Entity 由来の制約」が食い違ったまま気付かれない —— である。</p>
 *
 * <h2>本テストが「fail」ではなく「報告」である理由</h2>
 * <p>本テストは <b>まだ是正されていない食い違いの実測件数が不明</b>な段階で新設した。
 * 件数次第で「即座に是正する」か「別 issue へ送って段階的に潰す」かが変わるため、
 * 実測結果を確定させるまでは CI を赤くしない。件数を見て判断した後、
 * 是正が完了した時点で本テストを {@code fail} に切り替える（またはゼロ件を固定する
 * 番人へ格上げする）べきである。<b>大量に出た場合に勝手に allowlist で黙らせてはならない</b>
 * ——それは本テストの存在理由そのものを損なう。</p>
 *
 * <h2>対応づけできない Entity・列（判定不能）</h2>
 * <p>次のいずれかに該当する Entity・列は「判定不能」として一覧に出す。黙って無視しない:</p>
 * <ul>
 *   <li>{@code @Table(name = ...)} を明示していない Entity（Hibernate の暗黙命名規則は
 *       複数形化ルール等が環境依存で静的解析だけでは確定できないため）</li>
 *   <li>{@code @Column}/{@code @JoinColumn} の属性が複雑で正規表現で解析できないフィールド</li>
 *   <li>migration 側に対応する列が見当たらない（列名不一致・追跡不能な RENAME 等）</li>
 * </ul>
 *
 * <h2>命名規約変換</h2>
 * <p>{@code @Column(name=...)} 省略時は Hibernate の既定物理命名規則と同じ
 * キャメル→スネークケース変換（{@code userId} → {@code user_id}）を適用する。</p>
 *
 * <h2>限界（既知の非網羅性）</h2>
 * <p>本テストは {@code javaparser} 等の AST パーサーに依存せず正規表現でソースを走査する
 * 軽量実装である。複数フィールドを1行にまとめた宣言・アノテーションと修飾子の間に
 * コメントを挟む書き方などは取りこぼす可能性がある。取りこぼしは「食い違いなし」ではなく
 * 「その列を集計対象に含められなかった」ことを意味する（過少検出の可能性を残す）。</p>
 */
class MigrationEntityNullableConsistencyReportTest {

    private static final Path MIGRATION_DIR = Paths.get("src", "main", "resources", "db", "migration");
    private static final Path ENTITY_ROOT = Paths.get("src", "main", "java");

    private static final Pattern FILE_VERSION = Pattern.compile("^V(\\d+)\\.(\\d+)__");

    @Test
    @DisplayName("報告: migration最終状態とEntity @Column(nullable=)の食い違いを実測する（fail しない・探索用）")
    void migrationとEntityのNULL許容の食い違いを実測して報告する() throws IOException {
        Map<String, Boolean> migrationNullable = buildMigrationNullableMap();
        EntityScanResult entityResult = scanEntities();

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, EntityColumnInfo> e : entityResult.columns.entrySet()) {
            String key = e.getKey();
            EntityColumnInfo info = e.getValue();
            Boolean migNullable = migrationNullable.get(key);
            if (migNullable == null) {
                continue; // migration 側に列が見当たらない（判定不能。別カウントで報告）
            }
            if (!migNullable.equals(info.nullable)) {
                mismatches.add(String.format(Locale.ROOT,
                        "%s: migration側=%s / Entity側=%s（%s#%s）",
                        key, migNullable ? "NULL許容" : "NOT NULL",
                        info.nullable ? "NULL許容" : "NOT NULL",
                        info.className, info.fieldName));
            }
        }
        mismatches.sort(Comparator.naturalOrder());

        System.out.println("=== [issue #2545 マスター承認済み新規要件] "
                + "migration最終状態 × Entity @Column(nullable) 食い違い実測 ===");
        System.out.println("走査した @Table 明示 Entity 数        = " + entityResult.mappedEntityCount);
        System.out.println("判定不能（@Table 未明示）Entity 数     = " + entityResult.unmappableEntities.size());
        System.out.println("走査対象 _id 以外含む全カラム数        = " + entityResult.columns.size());
        System.out.println("食い違い件数                          = " + mismatches.size());
        System.out.println("--- 判定不能 Entity 一覧（先頭50件） ---");
        entityResult.unmappableEntities.stream().sorted().limit(50)
                .forEach(s -> System.out.println("  ? " + s));
        System.out.println("--- 食い違い一覧（先頭200件） ---");
        mismatches.stream().limit(200).forEach(s -> System.out.println("  ✗ " + s));

        // 本テストは意図的に fail しない（javadoc 参照）。件数はマスターの判断待ち。
        assertThat(mismatches).isNotNull();
    }

    // =====================================================================
    // migration 側: 最終状態の nullable マップを構築する
    // =====================================================================

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
    private static final Pattern DROP_COLUMN = Pattern.compile(
            "^DROP\\s+(?:COLUMN\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_TABLE_RENAME_TO = Pattern.compile(
            "^RENAME\\s+(?:TO|AS)\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME_TABLE_TO = Pattern.compile(
            "RENAME\\s+TABLE\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+TO\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TABLE = Pattern.compile(
            "^DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIMARY_KEY_INLINE = Pattern.compile(
            "PRIMARY\\s+KEY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIMARY_KEY_TABLE_LEVEL = Pattern.compile(
            "^PRIMARY\\s+KEY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    /** table.column -> 最終的な nullable（true=NULL許容 / false=NOT NULL）。 */
    static Map<String, Boolean> buildMigrationNullableMap() throws IOException {
        Map<String, Boolean> state = new LinkedHashMap<>();

        for (Path file : listMigrationFilesInApplyOrder(MIGRATION_DIR)) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String sql = SqlTextScanningUtils.blankOutTemporaryTables(SqlTextScanningUtils.stripComments(raw));

            for (String statement : SqlTextScanningUtils.splitStatements(sql)) {
                String trimmed = statement.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }

                Matcher ct = CREATE_TABLE.matcher(statement);
                if (ct.find()) {
                    handleCreateTable(statement, ct.group(1).toLowerCase(Locale.ROOT), state);
                    continue;
                }

                Matcher dt = DROP_TABLE.matcher(trimmed);
                if (dt.find()) {
                    String table = dt.group(1).toLowerCase(Locale.ROOT) + ".";
                    state.keySet().removeIf(k -> k.startsWith(table));
                    continue;
                }

                Matcher rt = RENAME_TABLE_TO.matcher(trimmed);
                if (rt.find()) {
                    renameTable(state, rt.group(1).toLowerCase(Locale.ROOT), rt.group(2).toLowerCase(Locale.ROOT));
                    continue;
                }

                Matcher at = ALTER_TABLE_HEAD.matcher(trimmed);
                if (at.find()) {
                    handleAlterTable(at.group(1).toLowerCase(Locale.ROOT), at.group(2), state);
                }
            }
        }
        return state;
    }

    private static void renameTable(Map<String, Boolean> state, String oldName, String newName) {
        List<String> keys = new ArrayList<>(state.keySet());
        for (String k : keys) {
            if (k.startsWith(oldName + ".")) {
                Boolean v = state.remove(k);
                state.put(newName + k.substring(oldName.length()), v);
            }
        }
    }

    private static void handleCreateTable(String statement, String table, Map<String, Boolean> state) {
        String body = extractParenBody(statement, statement.toUpperCase(Locale.ROOT).indexOf("CREATE"));
        if (body == null) {
            return;
        }
        List<String> parts = splitTopLevelCommas(body);
        List<String> pkColumns = new ArrayList<>();
        for (String part : parts) {
            String def = part.strip();
            if (def.isEmpty()) {
                continue;
            }
            Matcher pk = PRIMARY_KEY_TABLE_LEVEL.matcher(def);
            if (pk.find()) {
                for (String col : pk.group(1).split(",")) {
                    pkColumns.add(col.replaceAll("[`\"\\s]", "").toLowerCase(Locale.ROOT));
                }
                continue;
            }
            if (isTableLevelClause(def)) {
                continue;
            }
            Matcher colMatch = Pattern.compile("^[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+(.*)$", Pattern.DOTALL).matcher(def);
            if (!colMatch.find()) {
                continue;
            }
            String column = colMatch.group(1).toLowerCase(Locale.ROOT);
            String typeDecl = colMatch.group(2);
            state.put(table + "." + column, isNullable(typeDecl));
        }
        for (String col : pkColumns) {
            state.put(table + "." + col, false);
        }
    }

    private static void handleAlterTable(String table, String clausesText, Map<String, Boolean> state) {
        for (String clauseRaw : splitTopLevelCommas(clausesText)) {
            String clause = clauseRaw.strip();
            if (clause.isEmpty()) {
                continue;
            }

            Matcher add = ADD_COLUMN.matcher(clause);
            if (add.find()) {
                state.put(table + "." + add.group(1).toLowerCase(Locale.ROOT), isNullable(add.group(2)));
                continue;
            }
            Matcher mod = MODIFY_COLUMN.matcher(clause);
            if (mod.find()) {
                state.put(table + "." + mod.group(1).toLowerCase(Locale.ROOT), isNullable(mod.group(2)));
                continue;
            }
            Matcher chg = CHANGE_COLUMN.matcher(clause);
            if (chg.find()) {
                state.remove(table + "." + chg.group(1).toLowerCase(Locale.ROOT));
                state.put(table + "." + chg.group(2).toLowerCase(Locale.ROOT), isNullable(chg.group(3)));
                continue;
            }
            Matcher drop = DROP_COLUMN.matcher(clause);
            if (drop.find()) {
                state.remove(table + "." + drop.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            Matcher renameTbl = ALTER_TABLE_RENAME_TO.matcher(clause);
            if (renameTbl.find()) {
                renameTable(state, table, renameTbl.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            Matcher pkAdd = Pattern.compile("^ADD\\s+PRIMARY\\s+KEY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
                    .matcher(clause);
            if (pkAdd.find()) {
                for (String col : pkAdd.group(1).split(",")) {
                    state.put(table + "." + col.replaceAll("[`\"\\s]", "").toLowerCase(Locale.ROOT), false);
                }
            }
        }
    }

    private static boolean isNullable(String typeDecl) {
        String upper = typeDecl.toUpperCase(Locale.ROOT);
        if (upper.contains("NOT NULL")) {
            return false;
        }
        if (PRIMARY_KEY_INLINE.matcher(upper).find()) {
            return false;
        }
        return true;
    }

    private static boolean isTableLevelClause(String def) {
        String upper = def.toUpperCase(Locale.ROOT);
        return upper.startsWith("UNIQUE") || upper.startsWith("KEY ") || upper.startsWith("KEY(")
                || upper.startsWith("INDEX") || upper.startsWith("CONSTRAINT")
                || upper.startsWith("FOREIGN KEY") || upper.startsWith("CHECK")
                || upper.startsWith("FULLTEXT") || upper.startsWith("SPATIAL");
    }

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
        assertTrue(Files.isDirectory(dir), "マイグレーションディレクトリが見つからない: " + dir.toAbsolutePath());
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

    // =====================================================================
    // Entity 側: @Table / @Column(nullable=) / @JoinColumn(nullable=) を走査する
    // =====================================================================

    private record EntityColumnInfo(boolean nullable, String className, String fieldName) {
    }

    private static final class EntityScanResult {
        final Map<String, EntityColumnInfo> columns = new LinkedHashMap<>();
        final List<String> unmappableEntities = new ArrayList<>();
        int mappedEntityCount = 0;
    }

    private static final Pattern TABLE_ANN = Pattern.compile(
            "@Table\\s*\\(\\s*name\\s*=\\s*\"([A-Za-z0-9_]+)\"");
    private static final Pattern ENTITY_ANN = Pattern.compile("@Entity\\b");
    private static final Pattern CLASS_DECL = Pattern.compile("\\bclass\\s+([A-Za-z0-9_]+)\\b");

    /** {@code @Column}/{@code @JoinColumn} → 直後のフィールド宣言を1組として拾う。 */
    private static final Pattern COLUMN_FIELD = Pattern.compile(
            "@(Column|JoinColumn)\\s*\\(([^)]*)\\)"
                    + "(?:\\s*@[A-Za-z0-9_.]+(?:\\([^)]*\\))?)*"
                    + "\\s*(?:private|protected|public)\\s+[A-Za-z0-9_<>\\[\\],.\\s?]+?\\s+([A-Za-z0-9_]+)\\s*[;=]",
            Pattern.DOTALL);

    static EntityScanResult scanEntities() throws IOException {
        EntityScanResult result = new EntityScanResult();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(ENTITY_ROOT)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }

        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (!ENTITY_ANN.matcher(content).find()) {
                continue;
            }
            Matcher classMatch = CLASS_DECL.matcher(content);
            String className = classMatch.find() ? classMatch.group(1) : file.getFileName().toString();

            Matcher tableMatch = TABLE_ANN.matcher(content);
            if (!tableMatch.find()) {
                result.unmappableEntities.add(className + "（@Table(name=...) 未明示: " + file + "）");
                continue;
            }
            result.mappedEntityCount++;
            String table = tableMatch.group(1).toLowerCase(Locale.ROOT);

            Matcher fieldMatch = COLUMN_FIELD.matcher(content);
            while (fieldMatch.find()) {
                String attrs = fieldMatch.group(2);
                String fieldName = fieldMatch.group(3);

                Matcher nameAttr = Pattern.compile("name\\s*=\\s*\"([A-Za-z0-9_]+)\"").matcher(attrs);
                String column = nameAttr.find() ? nameAttr.group(1).toLowerCase(Locale.ROOT)
                        : camelToSnake(fieldName);

                Matcher nullableAttr = Pattern.compile("nullable\\s*=\\s*(true|false)").matcher(attrs);
                // JPA既定: nullable属性省略時は true（NULL許容）
                boolean nullable = !nullableAttr.find() || Boolean.parseBoolean(nullableAttr.group(1));

                result.columns.put(table + "." + column, new EntityColumnInfo(nullable, className, fieldName));
            }
        }
        return result;
    }

    /** Hibernate 既定物理命名規則と同じキャメル→スネークケース変換。 */
    private static String camelToSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
