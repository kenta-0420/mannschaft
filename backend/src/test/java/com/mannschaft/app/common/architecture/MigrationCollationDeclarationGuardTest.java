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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 新規 migration の {@code CREATE TABLE} に照合順序の明示宣言を強制する番人（issue #2589）。
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
 * <h2>{@code SchemaCollationConsistencyIT} との役割分担</h2>
 * <ul>
 *   <li>本テスト … <b>静的</b>。Docker 不要・数十ミリ秒。書いた瞬間に落ちるので開発者への即時フィードバックになる。</li>
 *   <li>{@code SchemaCollationConsistencyIT} … <b>動的</b>。本番と同じ照合順序で Flyway を実際に流し、
 *       適用<em>後</em>の実スキーマ全体を検証する。{@code ALTER} による後からの変更や、
 *       本テストの正規表現が読み違えた場合も含めて最終的な事実を押さえる。</li>
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

    /** ファイル名から major バージョンを取り出す。 */
    private static final Pattern MAJOR = Pattern.compile("^V(\\d+)\\.");

    /**
     * {@code CREATE TABLE <name>} の検出。
     * {@code CREATE TEMPORARY TABLE} は接続内で閉じており JOIN 相手にならないので除外する。
     */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?([A-Za-z0-9_]+)[`\"]?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("統一migration以降に追加されたCREATE TABLEは照合順序を明示宣言している")
    void 新規テーブルは照合順序を明示宣言する() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path file : listMigrationFiles()) {
            String name = file.getFileName().toString();
            Matcher mm = MAJOR.matcher(name);
            if (!mm.find() || Integer.parseInt(mm.group(1))
                    < SchemaCollationPolicy.UNIFICATION_MIGRATION_MAJOR) {
                continue; // 既存資産（Flyway チェックサム制約により修正不能）
            }

            String sql = SqlTextScanningUtils.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String statement : SqlTextScanningUtils.splitStatements(sql)) {
                if (statement.toUpperCase(Locale.ROOT).contains("CREATE TEMPORARY TABLE")) {
                    continue;
                }
                Matcher ct = CREATE_TABLE.matcher(statement);
                if (!ct.find()) {
                    continue;
                }
                String table = ct.group(1);
                // 表定義の閉じ括弧以降（テーブルオプション部）に COLLATE=<統一値> があること
                int close = statement.lastIndexOf(')');
                String options = close >= 0 ? statement.substring(close) : "";
                if (!collationMatches(options)) {
                    violations.add(name + " : " + table);
                }
            }
        }

        if (violations.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("照合順序を明示宣言していない CREATE TABLE があります（issue #2589）。\n")
                .append("宣言を省くとサーバ変数 collation_server を継承し、本番 RDS とローカル docker で\n")
                .append("同じ表の照合順序が変わります。その表を他表と JOIN して文字列列を比較すると\n")
                .append("『ローカルでは通るのに本番だけ Illegal mix of collations で落ちる』障害になります。\n")
                .append("CREATE TABLE の末尾に次を付けてください:\n")
                .append("  ) ENGINE=InnoDB DEFAULT CHARSET=")
                .append(SchemaCollationPolicy.UNIFIED_CHARSET)
                .append(" COLLATE=").append(SchemaCollationPolicy.UNIFIED_COLLATION)
                .append(";\n違反:\n");
        for (String v : violations) {
            sb.append("  ✗ ").append(v).append('\n');
        }
        fail(sb.toString());
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

    private static List<Path> listMigrationFiles() throws IOException {
        assertTrue(Files.isDirectory(MIGRATION_DIR),
                "マイグレーションディレクトリが見つからない: " + MIGRATION_DIR.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(MIGRATION_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("V") && n.toLowerCase(Locale.ROOT).endsWith(".sql");
                    })
                    .forEach(files::add);
        }
        return files;
    }
}
