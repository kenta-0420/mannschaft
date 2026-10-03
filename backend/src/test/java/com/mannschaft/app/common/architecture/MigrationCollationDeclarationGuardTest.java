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
 * 新規 migration の {@code CREATE TABLE} に照合順序の明示宣言を強制する番人（issue #2589）。
 * issue #2656 で「宣言は非統一だが後続 migration で統一先へ変換済み」を正当な合格として
 * 見分けられるよう精緻化した（migration 群を適用順に横断する形へ拡張）。
 *
 * <h2>背景</h2>
 * <p>照合順序を宣言しない表は MySQL のサーバ変数 {@code collation_server} を継承する。
 * 本番 RDS とローカル docker でこの変数が食い違っていたため、同じ DDL から
 * 環境ごとに違う照合順序のスキーマが生まれ、
 * {@code MyScopeFolderItemRepository#aggregateFolderUnreadCounts} が本番だけ
 * {@code Illegal mix of collations} で落ちた。
 * {@code V175.20260804134628__unify_table_collation.sql} が既存スキーマを統一し
 * {@code ALTER DATABASE} で既定も固定したが、それだけでは
 * <b>「今後また宣言を忘れる」</b>ことを止められない。本テストがそれを機械的に拒否する。</p>
 *
 * <h2>issue #2656: なぜ「ファイル単体判定」から「横断判定」へ変えたか</h2>
 * <p>統一直後に入った migration が非統一の照合順序を宣言した実例（issue #2589 / PR #2591）で、
 * 「後続 migration で ALTER して統一先へ変換する」という取り得べき対応（案A）を、
 * 従来のファイル単体判定の番人は正当な合格として表現できなかった
 * （宣言ファイルだけを見るため、後続の変換を認識しようがない）。
 * 本番適用済みの migration はチェックサム制約で書き換えられないため、本番稼働後は
 * 案Aしか選べなくなる。そこで判定を「migration 群を適用順に横断し、各表が
 * <b>最終的にどの照合順序へ収束するか</b>」を追う形に拡張した。</p>
 *
 * <h2>解釈対象としたALTER構文・意図的に解釈しない構文</h2>
 * <p>変換の書き方は複数あるが、本番人が解釈するのは
 * {@code ALTER TABLE <name> CONVERT TO CHARACTER SET <charset> [COLLATE <collation>]}
 * のみである。CREATE TABLE 末尾の COLLATE 宣言と同じ「表全体」の粒度で最終状態を
 * 機械的に断定できるのがこの構文だけだからである。
 * {@code MODIFY COLUMN ... COLLATE ...}（列単位）や {@code ALTER DATABASE ... COLLATE ...}
 * （データベース既定。CREATE TABLE が既に明示宣言している以上、表の照合順序を上書きしない）は
 * <b>意図的に解釈しない</b>。解釈しない構文に遭遇しても pending 状態は変化しないため、
 * 安全側（違反のまま）に倒れる ―― 黙って合格にはならない。</p>
 *
 * <h2>テーブルの DROP・リネームで追跡が壊れた場合</h2>
 * <p>本テストはテーブル名をキーに pending（違反候補）を追跡するため、
 * {@code DROP TABLE} や {@code RENAME TABLE ... TO ...} / {@code ALTER TABLE ... RENAME TO ...}
 * が pending 中の表に対して行われると、それ以降その表を旧名で正しく変換したとしても
 * 追跡が繋がらない。これは意図的な安全側フォールバックであり、該当表は違反として
 * 報告され続ける（メッセージにその旨を付記する）。真に安全に倒すには pending から
 * 外さず「以降追跡不能」と明記するだけで十分であり、追加のロジックは持たない。</p>
 *
 * <h2>{@code SchemaCollationConsistencyIT} との役割分担</h2>
 * <ul>
 *   <li>本テスト … <b>静的</b>。Docker 不要・数十ミリ秒。書いた瞬間に落ちるので開発者への即時フィードバックになる。</li>
 *   <li>{@code SchemaCollationConsistencyIT} … <b>動的</b>。本番と同じ照合順序で Flyway を実際に流し、
 *       適用<em>後</em>の実スキーマ全体を検証する。本テストの正規表現が読み違えた場合や、
 *       動的 SQL（{@code PREPARE}/{@code EXECUTE} 経由の ALTER）のように本テストが原理的に
 *       解釈できない変換も含めて最終的な事実を押さえる。</li>
 * </ul>
 * <p>静的な網は速いが SQL をテキストとして読むため取りこぼしうる。動的な網は確実だが遅い。
 * 両方を張ることで速さと確実さを両取りしている。</p>
 *
 * <h2>既存 migration を免除する理由</h2>
 * <p>Flyway は適用済み migration のチェックサムを検証するため、
 * 既存ファイルの中身を書き換えると適用済み環境が起動不能になる。
 * したがって {@link SchemaCollationPolicy#UNIFICATION_MIGRATION_MAJOR} より前は
 * 物理的に修正できない。免除しても実害が無いのは、V175 が適用後の実スキーマを統一し、
 * {@code SchemaCollationConsistencyIT} がその結果を検証しているからである
 * （＝欠陥を見逃す免罪符ではなく、別の網でカバー済みという役割分担）。</p>
 */
class MigrationCollationDeclarationGuardTest {

    private static final Path MIGRATION_DIR =
            Paths.get("src", "main", "resources", "db", "migration");

    /** ファイル名から適用順ソート用の major.minor を取り出す（例: {@code V175.20260804134628__...}）。 */
    private static final Pattern FILE_VERSION = Pattern.compile("^V(\\d+)\\.(\\d+)__");

    /**
     * {@code CREATE TABLE <name>} の検出。
     * {@code CREATE TEMPORARY TABLE} は接続内で閉じており JOIN 相手にならないので除外する。
     */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** クラス javadoc「解釈対象としたALTER構文」を参照。表全体を変換する構文のみを解釈する。 */
    private static final Pattern ALTER_CONVERT = Pattern.compile(
            "ALTER\\s+TABLE\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+CONVERT\\s+TO\\s+CHARACTER\\s+SET\\s+"
                    + "[`'\"]?([A-Za-z0-9_]+)[`'\"]?(?:\\s+COLLATE\\s+[`'\"]?([A-Za-z0-9_]+)[`'\"]?)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RENAME_TABLE_TO = Pattern.compile(
            "RENAME\\s+TABLE\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+TO\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_TABLE_RENAME_TO = Pattern.compile(
            "ALTER\\s+TABLE\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?\\s+RENAME\\s+(?:TO|AS)\\s+[`\"]?([A-Za-z0-9_]+)[`\"]?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DROP_TABLE = Pattern.compile(
            "DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("統一migration以降のCREATE TABLEは照合順序を明示宣言している、または後続migrationで統一先へ変換されている")
    void 新規テーブルは照合順序を明示宣言するか後続で統一先へ変換されている() throws IOException {
        List<Path> files = listMigrationFilesInApplyOrder(MIGRATION_DIR);
        List<String> violations = scan(files, SchemaCollationPolicy.UNIFICATION_MIGRATION_MAJOR);

        if (violations.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("照合順序が統一先で確定していない CREATE TABLE があります（issue #2589 / #2656）。\n")
                .append("宣言を省く、または非統一の照合順序を宣言したまま後続 migration で\n")
                .append("統一先へ変換していないと、本番 RDS とローカル docker で同じ表の照合順序が変わります。\n")
                .append("その表を他表と JOIN して文字列列を比較すると\n")
                .append("『ローカルでは通るのに本番だけ Illegal mix of collations で落ちる』障害になります。\n")
                .append("CREATE TABLE の末尾に次を付けるか:\n")
                .append("  ) ENGINE=InnoDB DEFAULT CHARSET=")
                .append(SchemaCollationPolicy.UNIFIED_CHARSET)
                .append(" COLLATE=").append(SchemaCollationPolicy.UNIFIED_COLLATION)
                .append(";\n")
                .append("後続 migration で次を追加してください:\n")
                .append("  ALTER TABLE <table> CONVERT TO CHARACTER SET ")
                .append(SchemaCollationPolicy.UNIFIED_CHARSET)
                .append(" COLLATE ").append(SchemaCollationPolicy.UNIFIED_COLLATION)
                .append(";\n違反:\n");
        for (String v : violations) {
            sb.append("  ✗ ").append(v).append('\n');
        }
        fail(sb.toString());
    }

    // =====================================================================
    // issue #2656: 精緻化後の判定ロジックが両方向に正しく機能することを実証するfixtureテスト。
    // =====================================================================

    @Test
    @DisplayName("案A: 非統一の宣言＋後続migrationで統一先へ変換済みは合格になる")
    void 案A非統一宣言でも後続で統一先へ変換済みなら合格(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_case_a_ok.sql",
                "CREATE TABLE case_a_ok (\n"
                        + "  id BIGINT PRIMARY KEY\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n");
        writeFile(dir, "V900.20260101000001__convert_case_a_ok.sql",
                "ALTER TABLE case_a_ok CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir), 900);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("案A失敗形: 非統一の宣言のまま後続で変換されていなければ違反として検出する")
    void 非統一宣言のまま変換されていなければ違反(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_case_a_ng.sql",
                "CREATE TABLE case_a_ng (\n"
                        + "  id BIGINT PRIMARY KEY\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir), 900);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("case_a_ng");
    }

    @Test
    @DisplayName("宣言なしCREATE TABLEは従来通り違反として検出する（検出力の回帰防止）")
    void 宣言なしCREATE_TABLEは違反(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_no_collation.sql",
                "CREATE TABLE no_collation (\n"
                        + "  id BIGINT PRIMARY KEY\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir), 900);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("no_collation");
    }

    @Test
    @DisplayName("統一先以外へのALTER TABLE CONVERT TOは変換とみなさず違反のまま扱う")
    void 統一先以外への変換は違反のまま(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_case_wrong_target.sql",
                "CREATE TABLE case_wrong_target (\n"
                        + "  id BIGINT PRIMARY KEY\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n");
        writeFile(dir, "V900.20260101000001__convert_case_wrong_target.sql",
                "ALTER TABLE case_wrong_target CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir), 900);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("case_wrong_target");
    }

    @Test
    @DisplayName("MODIFY COLUMN COLLATEなど解釈対象外のALTERは黙って合格にせず違反のまま扱う")
    void 解釈対象外のALTERは違反のまま(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_case_unmodeled_alter.sql",
                "CREATE TABLE case_unmodeled_alter (\n"
                        + "  name VARCHAR(64)\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n");
        writeFile(dir, "V900.20260101000001__modify_case_unmodeled_alter.sql",
                "ALTER TABLE case_unmodeled_alter MODIFY COLUMN name VARCHAR(64) "
                        + "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir), 900);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("case_unmodeled_alter");
    }

    @Test
    @DisplayName("違反表がリネームされると追跡不能である旨を付記して安全側で違反のまま扱う")
    void リネームされた違反表は追跡不能として違反のまま(@TempDir Path dir) throws IOException {
        writeFile(dir, "V900.20260101000000__create_case_renamed.sql",
                "CREATE TABLE case_renamed_old (\n"
                        + "  id BIGINT PRIMARY KEY\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n");
        writeFile(dir, "V900.20260101000001__rename_case_renamed.sql",
                "RENAME TABLE case_renamed_old TO case_renamed_new;\n");
        // 新名に対して統一先へ変換していても、旧名で追跡していた本テストには繋がらない。
        writeFile(dir, "V900.20260101000002__convert_case_renamed.sql",
                "ALTER TABLE case_renamed_new CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n");

        List<String> violations = scan(listMigrationFilesInApplyOrder(dir), 900);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("case_renamed_old");
        assertThat(violations.get(0)).contains("追跡不能");
    }

    private static void writeFile(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    // =====================================================================
    // 判定ロジック本体
    // =====================================================================

    /**
     * migration 群を{@code filesInApplyOrder}の順（＝適用順）に横断し、
     * {@code unificationMajor}以降のCREATE TABLEについて
     * 「宣言が統一先」または「後続 migration の {@code ALTER TABLE ... CONVERT TO} で
     * 統一先へ変換済み」のいずれでもない表を違反として返す。
     */
    static List<String> scan(List<Path> filesInApplyOrder, int unificationMajor) throws IOException {
        // 表名 -> 違反理由（LinkedHashMapで検出順を保つ。後続ALTERで解消されればremoveする）
        Map<String, String> pending = new LinkedHashMap<>();

        for (Path file : filesInApplyOrder) {
            String name = file.getFileName().toString();
            Matcher fv = FILE_VERSION.matcher(name);
            if (!fv.find() || Integer.parseInt(fv.group(1)) < unificationMajor) {
                continue; // 既存資産（Flyway チェックサム制約により修正不能）
            }

            String sql = SqlTextScanningUtils.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String statement : SqlTextScanningUtils.splitStatements(sql)) {
                if (statement.toUpperCase(Locale.ROOT).contains("CREATE TEMPORARY TABLE")) {
                    continue;
                }

                Matcher ct = CREATE_TABLE.matcher(statement);
                if (ct.find()) {
                    String table = ct.group(1);
                    int close = statement.lastIndexOf(')');
                    String options = close >= 0 ? statement.substring(close) : "";
                    if (collationMatches(options)) {
                        pending.remove(table);
                    } else {
                        pending.put(table, name + " : " + table
                                + "（CREATE TABLE で照合順序が未宣言または非統一）");
                    }
                    continue;
                }

                Matcher alt = ALTER_CONVERT.matcher(statement);
                if (alt.find()) {
                    String table = alt.group(1);
                    String collation = alt.group(3);
                    if (collation != null
                            && SchemaCollationPolicy.UNIFIED_COLLATION.equalsIgnoreCase(collation)) {
                        pending.remove(table);
                    } else if (pending.containsKey(table)) {
                        pending.put(table, name + " : " + table
                                + "（ALTER TABLE CONVERT TO で統一先以外の照合順序へ変換されており未解消）");
                    }
                    continue;
                }

                markUntrackableIfPending(statement, name, pending, RENAME_TABLE_TO);
                markUntrackableIfPending(statement, name, pending, ALTER_TABLE_RENAME_TO);
                markUntrackableIfPending(statement, name, pending, DROP_TABLE);
            }
        }

        return new ArrayList<>(pending.values());
    }

    /**
     * {@code pattern}（RENAME/DROP系）が表す旧表名が現在 pending 中であれば、
     * 「これ以降その表名での追跡は継続できない」旨をメッセージに付記した上で
     * pending から外さない（＝安全側のまま違反として残す。クラス javadoc参照）。
     */
    private static void markUntrackableIfPending(
            String statement, String fileName, Map<String, String> pending, Pattern pattern) {
        Matcher m = pattern.matcher(statement);
        while (m.find()) {
            String table = m.group(1);
            String current = pending.get(table);
            if (current != null && !current.contains("追跡不能")) {
                pending.put(table, current + " ※" + fileName
                        + " でDROP/RENAMEが検出され以降追跡不能（安全側で違反のまま扱う）");
            }
        }
    }

    /** テーブルオプション部が統一照合順序を宣言しているか。 */
    private static boolean collationMatches(String options) {
        Matcher m = Pattern.compile("COLLATE\\s*=?\\s*[`'\"]?([A-Za-z0-9_]+)",
                Pattern.CASE_INSENSITIVE).matcher(options);
        return m.find()
                && SchemaCollationPolicy.UNIFIED_COLLATION.equalsIgnoreCase(m.group(1));
    }

    // コメント除去・文分割（引用符内の ; を無視）は SqlTextScanningUtils（common.migration）に
    // 共通化してある（CMP-022: 独自の正規表現実装が引用符を考慮しておらず、
    // 文字列リテラルに ; を含む CREATE TABLE で誤分割する潜在欠陥があった）。

    /** migration ファイルを適用順（major, minor の数値昇順）に並べて返す。 */
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

    /** ファイル名から {@code group}（1=major, 2=minor）を数値で取り出す。解釈不能な名前は末尾に回す。 */
    private static long versionPart(String fileName, int group) {
        Matcher m = FILE_VERSION.matcher(fileName);
        return m.find() ? Long.parseLong(m.group(group)) : Long.MAX_VALUE;
    }
}
