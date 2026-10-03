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
 * {@code @JoinColumn(nullable=...)} の食い違いを検出する<b>番人テスト</b>
 * （符号揃え 第三波・issue #2545 で見つかった同型の盲点、マスター承認済みの新規要件）。
 *
 * <h2>なぜこの検出が必要か</h2>
 * <p>Entity 側で {@code nullable = false} を書き忘れると、テストは Entity 由来のスキーマ
 * （{@code ddl-auto=create}）で走るため <b>必須列の詰め忘れが全 IT をすり抜け、実機でのみ 500</b> になる
 * （CMP-021 の実例）。符号性の盲点とまったく同じ構造 —— 「本番 DDL の実際の制約」と
 * 「テストが使う Entity 由来の制約」が食い違ったまま気付かれない —— である。</p>
 *
 * <h2>番人への昇格（第四波・2026-08-13）</h2>
 * <p>本テストは当初「まだ是正されていない食い違いの実測件数が不明」な段階で報告専用
 * （fail しない）として新設された。第四波でマスター承認済み新規要件として実測した結果、
 * 25 件の食い違い（うち 24 件は危険な向き＝migration NOT NULL・Entity nullable、
 * 1 件は逆向き＝migration が意図的に NULL 許容へ緩和した設計＝{@code match_events.period}）
 * を <b>すべて是正しゼロ件にした</b>ため、以後の再発を機械的に防ぐ番人へ昇格させた
 * （{@code assertThat(mismatches).isEmpty()}）。<b>allowlist による免罪符化は行っていない
 * ——ゼロ件になったから昇格した</b>のであり、残件を黙らせて昇格させたのではない。</p>
 *
 * <h2>検分差し戻し（P1〜P3）の是正・2026-08-14</h2>
 * <p>番人昇格直後の検分（Codex 独立検分＋殿の照合）で3件の重い指摘を受け、以下を是正した:</p>
 * <ul>
 *   <li><b>P1: DB 生成カラムへの {@code nullable=false} 追加は誤り。</b>
 *       {@code insertable=false && updatable=false} の DB 生成カラム（{@code STORED GENERATED
 *       ALWAYS AS} 等）は Hibernate が INSERT/UPDATE 文自体に含めないため
 *       {@code @Column(nullable=...)} が実質無意味であり、{@code ddl-auto=create} のテスト
 *       スキーマでは生成式が再現されず素の NOT NULL 列として作られるため、むしろ INSERT を
 *       全滅させる（{@code RecruitmentParticipantEntity#activeSubjectKey} で実測: 該当 IT
 *       29 件中 29 件が「Field 'active_subject_key' doesn't have a default value」で失敗する
 *       ことを確認）。このため DB 生成カラムは {@code insertable=false && updatable=false} と
 *       いう<b>機械的な条件のみ</b>（列名 allowlist は使わない）で突き合わせ対象から除外し、
 *       黙って消さず「DB生成カラム（突き合わせ対象外）」として別カウント・別一覧で報告する。</li>
 *   <li><b>P2: 走査漏れで番人が偽陰性を隠していた。</b>
 *       {@code @Column}/{@code @JoinColumn} の記述ごと削除された（暗黙マッピングになった）
 *       フィールドや、暗黙マッピングのまま新規追加された必須列は、アノテーション付き文字列
 *       にしか反応しない旧実装では検出漏れになりうる。素朴なスカラー型（後述の
 *       {@link #IMPLICIT_FIELD}）に限定してアノテーション無しフィールドも走査対象へ追加し、
 *       JPA既定の {@code nullable=true} として比較に含める。また「Entity 列があるのに
 *       migration 側に対応が見当たらない」「migration が NOT NULL と定める列なのに
 *       Entity 走査側に一切対応が無い」の両方向を、黙って {@code continue} せず件数・一覧
 *       として毎回出力する（現状ゼロ件を実測済み・将来増えても可視化される）。</li>
 *   <li><b>P3: 自前のコメント除去がテキストブロック非対応で偽陰性を再導入していた。</b>
 *       単純な {@code "} トグル方式では、テキストブロック（{@code """..."""}）本文に含まれる
 *       奇数個の生クォートで同期がずれ、後続の実コードを「文字列の内側」と誤認して丸ごと
 *       見失う（migration 版 {@code SqlTextScanningUtils} の欠陥と同型・過去に
 *       {@code JavaSourceScanningUtils} が実測して是正済みの欠陥）。自前実装
 *       {@code stripJavaComments} を廃し、実測済みの共通ユーティリティ
 *       {@link JavaSourceScanningUtils#maskCommentsOnly} を使う。</li>
 * </ul>
 *
 * <h2>対応づけできない Entity・列（判定不能）</h2>
 * <p>次のいずれかに該当する Entity・列は「判定不能」として一覧に出す。黙って無視しない:</p>
 * <ul>
 *   <li>{@code @Table(name = ...)} を明示していない Entity（Hibernate の暗黙命名規則は
 *       複数形化ルール等が環境依存で静的解析だけでは確定できないため）</li>
 *   <li>{@code @Column}/{@code @JoinColumn} の属性が複雑で正規表現で解析できないフィールド</li>
 *   <li>migration 側に対応する列が見当たらない（列名不一致・追跡不能な RENAME 等）</li>
 *   <li>DB 生成カラム（{@code insertable=false && updatable=false}）。突き合わせの意味が
 *       そもそも無いため対象外（P1）</li>
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
 * 「その列を集計対象に含められなかった」ことを意味する（過少検出の可能性を残す）。
 * 暗黙マッピングの走査（P2）は誤検出防止のためスカラー型ホワイトリスト方式であり、
 * 独自 enum 型等の暗黙マッピングは拾わない（過少検出を許容し過多検出を避ける設計判断）。
 * {@code @Embeddable} 経由でファイルをまたいでマッピングされる列は、本テストが Entity
 * ファイル単体を走査する方式である以上、構造的に「migration NOT NULL で Entity 側に無い列」
 * として現れうる（実測ではゼロ件だが、将来 Embeddable が導入されたら誤検出しうる限界として
 * 明記する）。</p>
 */
class MigrationEntityNullableConsistencyReportTest {

    private static final Path MIGRATION_DIR = Paths.get("src", "main", "resources", "db", "migration");
    private static final Path ENTITY_ROOT = Paths.get("src", "main", "java");

    private static final Pattern FILE_VERSION = Pattern.compile("^V(\\d+)\\.(\\d+)__");

    @Test
    @DisplayName("番人: migration最終状態とEntity @Column(nullable=)の食い違いはゼロ件であること")
    void migrationとEntityのNULL許容の食い違いはゼロ件であること() throws IOException {
        Map<String, Boolean> migrationNullable = buildMigrationNullableMap();
        EntityScanResult entityResult = scanEntities();

        List<String> mismatches = new ArrayList<>();
        // 検分 P2 是正: 「Entity側の列があるのに migration 側に対応する列が見当たらない」を
        // 黙って continue で握り潰さず明示的に一覧する（列名不一致・追跡不能な RENAME 等）。
        List<String> unmatchedEntityColumns = new ArrayList<>();
        for (Map.Entry<String, EntityColumnInfo> e : entityResult.columns.entrySet()) {
            String key = e.getKey();
            EntityColumnInfo info = e.getValue();
            Boolean migNullable = migrationNullable.get(key);
            if (migNullable == null) {
                unmatchedEntityColumns.add(key + "（" + info.className + "#" + info.fieldName + "）");
                continue;
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
        unmatchedEntityColumns.sort(Comparator.naturalOrder());

        // 検分 P2 是正・逆方向: @Table 明示できた Entity のテーブルについて、migration が
        // NOT NULL と定めている列のうち Entity 走査側に一切対応が無い（暗黙マッピング走査でも
        // 拾えなかった）ものを一覧する。@Embeddable 等ファイルをまたぐマッピングは本テストの
        // 走査対象外のため過検出しうる限界を javadoc に明記した上で、事実として報告する
        // （allowlistで黙らせない・件数を出す）。
        List<String> unmappedRequiredMigrationColumns = new ArrayList<>();
        for (Map.Entry<String, Boolean> m : migrationNullable.entrySet()) {
            String key = m.getKey();
            int dot = key.indexOf('.');
            if (dot < 0) {
                continue;
            }
            String table = key.substring(0, dot);
            boolean migNotNull = Boolean.FALSE.equals(m.getValue());
            if (migNotNull && entityResult.mappedTables.contains(table) && !entityResult.columns.containsKey(key)) {
                unmappedRequiredMigrationColumns.add(key);
            }
        }
        unmappedRequiredMigrationColumns.sort(Comparator.naturalOrder());

        System.out.println("=== [issue #2545 マスター承認済み新規要件] "
                + "migration最終状態 × Entity @Column(nullable) 食い違い実測 ===");
        System.out.println("走査した @Table 明示 Entity 数        = " + entityResult.mappedEntityCount);
        System.out.println("判定不能（@Table 未明示）Entity 数     = " + entityResult.unmappableEntities.size());
        System.out.println("走査対象 _id 以外含む全カラム数        = " + entityResult.columns.size());
        System.out.println("DB生成カラム（突き合わせ対象外）数     = " + entityResult.generatedColumns.size());
        System.out.println("食い違い件数                          = " + mismatches.size());
        System.out.println("Entity列にありmigration側に無い列数     = " + unmatchedEntityColumns.size());
        System.out.println("migration NOT NULLでEntity側に無い列数  = " + unmappedRequiredMigrationColumns.size());
        System.out.println("--- 判定不能 Entity 一覧（先頭50件） ---");
        entityResult.unmappableEntities.stream().sorted().limit(50)
                .forEach(s -> System.out.println("  ? " + s));
        System.out.println("--- DB生成カラム一覧（先頭50件） ---");
        entityResult.generatedColumns.stream().sorted().limit(50)
                .forEach(s -> System.out.println("  ~ " + s));
        System.out.println("--- 食い違い一覧（先頭200件） ---");
        mismatches.stream().limit(200).forEach(s -> System.out.println("  ✗ " + s));
        System.out.println("--- Entity列にありmigration側に無い列 一覧（先頭50件） ---");
        unmatchedEntityColumns.stream().limit(50).forEach(s -> System.out.println("  ? " + s));
        System.out.println("--- migration NOT NULLでEntity側に無い列 一覧（先頭50件） ---");
        unmappedRequiredMigrationColumns.stream().limit(50).forEach(s -> System.out.println("  ? " + s));

        // 番人（第四波で 25 件をゼロ件まで是正して昇格・javadoc 参照）。
        // 再発時はここで fail する（allowlist での黙らせは禁止）。
        assertThat(mismatches)
                .as("migration最終状態とEntity @Column(nullable=)の食い違い一覧")
                .isEmpty();
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
        // COMMENT '...' 等の引用符内テキストに「NOT NULL」という文言が含まれる列
        // （例: entitlements.revoked_at の COMMENT '取消日時。NOT NULL なら期間内でも無効'）を
        // 型宣言の NOT NULL 制約と誤認しないよう、判定前に引用符内を空白へ潰す。
        String blanked = blankQuotedLiterals(typeDecl);
        String upper = blanked.toUpperCase(Locale.ROOT);
        if (upper.contains("NOT NULL")) {
            return false;
        }
        if (PRIMARY_KEY_INLINE.matcher(upper).find()) {
            return false;
        }
        return true;
    }

    /** 引用符（{@code '} {@code "} {@code `}）で囲まれた部分を同じ長さの空白へ置換する。 */
    private static String blankQuotedLiterals(String text) {
        StringBuilder sb = new StringBuilder(text);
        int n = sb.length();
        for (int i = 0; i < n; i++) {
            char c = sb.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = SqlTextScanningUtils.skipQuoted(sb, i, c);
                for (int j = i; j <= end && j < sb.length(); j++) {
                    if (sb.charAt(j) != '\n') {
                        sb.setCharAt(j, ' ');
                    }
                }
                i = end;
            }
        }
        return sb.toString();
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
        /** DB 生成カラム（insertable=false && updatable=false）。突き合わせ対象外・機械的除外（検分 P1）。 */
        final List<String> generatedColumns = new ArrayList<>();
        /** {@code @Table(name=...)} で解決できたテーブル名（小文字）の集合。 */
        final java.util.Set<String> mappedTables = new java.util.LinkedHashSet<>();
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
            // Javadoc/行コメント中の文言（例: 「archive 用 @Entity を持たず」）を実際の
            // @Entity アノテーションと誤認しないよう、判定前にコメントを空白へ潰す
            // （NotificationAnonymizationEventListener で実際に誤検出した実例）。
            // テキストブロック（"""..."""）を単純な `"` トグルで扱うと本文中の奇数個の
            // 生クォートで同期がずれ後続コードを丸ごと見失う既知の欠陥があるため、
            // 自前実装ではなく実測済みの共通ユーティリティ JavaSourceScanningUtils を使う
            // （検分 P3 是正・車輪の再発明の除去）。
            String content = JavaSourceScanningUtils.maskCommentsOnly(Files.readString(file, StandardCharsets.UTF_8));
            if (!ENTITY_ANN.matcher(content).find()) {
                continue;
            }
            Matcher classMatch = CLASS_DECL.matcher(content);
            boolean classFound = classMatch.find();
            String className = classFound ? classMatch.group(1) : file.getFileName().toString();
            // 主 Entity クラス本体の開始位置（直後の最初の '{'）。ネストしたヘルパークラス
            // （例: @IdClass の複合キー POJO）内のフィールドを暗黙マッピングとして誤検出
            // しないよう、暗黙フィールド走査はこのブレース深さ判定で絞り込む（下記参照）。
            int classBodyStart = classFound ? content.indexOf('{', classMatch.end()) : -1;

            Matcher tableMatch = TABLE_ANN.matcher(content);
            if (!tableMatch.find()) {
                result.unmappableEntities.add(className + "（@Table(name=...) 未明示: " + file + "）");
                continue;
            }
            result.mappedEntityCount++;
            String table = tableMatch.group(1).toLowerCase(Locale.ROOT);
            result.mappedTables.add(table);

            List<String> annotatedFieldNames = new ArrayList<>();
            Matcher fieldMatch = COLUMN_FIELD.matcher(content);
            while (fieldMatch.find()) {
                String attrs = fieldMatch.group(2);
                String fieldName = fieldMatch.group(3);
                annotatedFieldNames.add(fieldName);

                Matcher nameAttr = Pattern.compile("name\\s*=\\s*\"([A-Za-z0-9_]+)\"").matcher(attrs);
                String column = nameAttr.find() ? nameAttr.group(1).toLowerCase(Locale.ROOT)
                        : camelToSnake(fieldName);

                // DB 生成カラム（insertable=false かつ updatable=false）は Hibernate が
                // INSERT/UPDATE 文自体に含めないため @Column(nullable=...) が実質無意味
                // （検分 P1 是正）。列名の allowlist ではなく、この2属性の組合せという
                // 機械的な条件のみで突き合わせ対象から除外する。除外しても黙って消さず
                // 別カウントで報告する。
                boolean insertableFalse = ATTR_FALSE.apply("insertable", attrs);
                boolean updatableFalse = ATTR_FALSE.apply("updatable", attrs);
                if (insertableFalse && updatableFalse) {
                    result.generatedColumns.add(table + "." + column + "（" + className + "#" + fieldName + "）");
                    continue;
                }

                Matcher nullableAttr = Pattern.compile("nullable\\s*=\\s*(true|false)").matcher(attrs);
                // JPA既定: nullable属性省略時は true（NULL許容）
                boolean nullable = !nullableAttr.find() || Boolean.parseBoolean(nullableAttr.group(1));

                result.columns.put(table + "." + column, new EntityColumnInfo(nullable, className, fieldName));
            }

            // 検分 P2 是正: @Column/@JoinColumn の記述ごと削除された（暗黙マッピングになった）
            // フィールドや、暗黙マッピングのまま新規追加された必須列を番人がすり抜けないよう、
            // アノテーション無しの素朴なスカラー型フィールドも拾う。JPA既定は nullable=true
            // なので、migration側がNOT NULLならここで確実に食い違いとして検出される。
            Matcher implicitMatch = IMPLICIT_FIELD.matcher(content);
            while (implicitMatch.find()) {
                String fieldName = implicitMatch.group(2);
                if (annotatedFieldNames.contains(fieldName)) {
                    continue; // 既に @Column/@JoinColumn で捕捉済み
                }
                if (classBodyStart < 0 || braceDepthAt(content, classBodyStart, implicitMatch.start()) != 0) {
                    // 主クラス本体の直下（深さ0）でない＝ネストしたヘルパークラス
                    // （@IdClass の複合キー POJO 等）内のフィールドのため対象外
                    // （VillageFestivalLivePostEntity.VillageFestivalLivePostId#festivalId で実際に誤検出した実例）。
                    continue;
                }
                int lookbackStart = Math.max(0, implicitMatch.start() - 400);
                String precedingBlock = content.substring(lookbackStart, implicitMatch.start());
                int lastStatementEnd = Math.max(precedingBlock.lastIndexOf(';'), precedingBlock.lastIndexOf('}'));
                String annotationWindow = precedingBlock.substring(Math.max(0, lastStatementEnd));
                if (RELATION_OR_TRANSIENT_ANN.matcher(annotationWindow).find()) {
                    continue; // リレーション/@Transient/@Version等はスカラー列ではない
                }
                String column = camelToSnake(fieldName);
                String key = table + "." + column;
                if (result.columns.containsKey(key) || result.generatedColumns.stream().anyMatch(g -> g.startsWith(key + "（"))) {
                    continue; // 別名で既に記録済み（同名衝突の回避）
                }
                result.columns.put(key, new EntityColumnInfo(true, className, fieldName + "（暗黙マッピング）"));
            }
        }
        return result;
    }

    /**
     * {@code classBodyStart}（主クラスの開始 {@code '{'} の位置）から {@code pos} までの
     * ブレース深さを数える。0 なら主クラス本体の直下、1 以上ならネストしたクラス/ブロック内。
     */
    private static int braceDepthAt(String content, int classBodyStart, int pos) {
        int depth = 0;
        for (int i = classBodyStart + 1; i < pos && i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth;
    }

    /** {@code attrName = false} が明示されているかを判定する小さなヘルパー。 */
    private interface AttrFalseChecker {
        boolean apply(String attrName, String attrs);
    }

    private static final AttrFalseChecker ATTR_FALSE = (attrName, attrs) -> {
        Matcher m = Pattern.compile(Pattern.quote(attrName) + "\\s*=\\s*(true|false)").matcher(attrs);
        return m.find() && "false".equals(m.group(1));
    };

    /**
     * アノテーション無しの素朴なスカラー型フィールド宣言を拾う（検分 P2 是正）。
     * 誤検出（リレーション・コレクション型を implicit 列として拾ってしまう）を避けるため、
     * 型は明確にスカラーと分かるものだけに絞ったホワイトリスト方式とする
     * （enum 型等の独自クラス名は対象外・過少検出を許容し過多検出を避ける設計判断）。
     */
    private static final Pattern IMPLICIT_FIELD = Pattern.compile(
            "(?:private|protected|public)\\s+"
                    + "(String|Long|Integer|Short|Boolean|boolean|int|long|short|byte|Byte"
                    + "|BigDecimal|BigInteger|LocalDate|LocalDateTime|LocalTime|Instant|UUID"
                    + "|Duration|Double|Float|double|float|char|Character)"
                    + "\\s+([A-Za-z0-9_]+)\\s*(?:=[^;]*)?;");

    /**
     * リレーション・コレクション・@Transient・@Version 等、通常の NOT NULL 突き合わせの
     * 対象にならないことを示す注釈群。{@code @Id}/{@code @GeneratedValue} も含める
     * ——DB の AUTO_INCREMENT/UUID 生成が値を決めるため、P1 で除外した DB 生成カラムと
     * 同じ理屈で {@code @Column(nullable=...)} の有無が実質無意味であり、本コードベースの
     * 大多数の Entity は {@code @Id @GeneratedValue(strategy=IDENTITY) private Long id;}
     * のように意図的に {@code @Column} を付けない慣習であるため（実測: 除外前は 359 件が
     * ほぼ全て `.id` の暗黙マッピング誤検出だった）。
     */
    private static final Pattern RELATION_OR_TRANSIENT_ANN = Pattern.compile(
            "@(ManyToOne|OneToOne|OneToMany|ManyToMany|ElementCollection|Embedded|EmbeddedId"
                    + "|Transient|Version|Column|JoinColumn|Id|GeneratedValue)\\b");

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
