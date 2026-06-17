package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * クロスドメイン外部キー（FK）の番人テスト（D-4）: <b>異なるドメインのテーブル間に
 * Foreign Key 制約を作ってはならない</b>。
 *
 * <p>設計指針: {@code CLAUDE.md}「DB 設計の原則 #1 / #2」—
 * 「異なるドメインのテーブル間に FK 制約を追加してはならない。参照整合性はアプリ層で
 * 保証する」「{@code ON DELETE CASCADE} は親子が同一ドメインに属する場合のみ許可」。
 *
 * <h2>検査対象</h2>
 * <p>{@code backend/src/main/resources/db/migration/*.sql} を<b>読むだけ</b>（書き換えない）。
 * 以下 2 構文の FK を正規表現で抽出する:
 * <ul>
 *   <li>(a) {@code CREATE TABLE} インラインの
 *       {@code FOREIGN KEY (...) REFERENCES <parent> (...)}</li>
 *   <li>(b) {@code ALTER TABLE <child> ADD CONSTRAINT ... FOREIGN KEY (...) REFERENCES <parent> (...)}</li>
 * </ul>
 * 同一 FK 句に付く {@code ON DELETE CASCADE|SET NULL} も同時に捕捉する。
 * 子テーブルは直近の {@code CREATE TABLE} / {@code ALTER TABLE} 対象、親テーブルは
 * {@code REFERENCES} の対象。両者を {@link TableDomainResolver} でドメイン解決する。
 *
 * <h2>ドメイン未知のスキップ</h2>
 * <p>子・親いずれかのテーブルがどの {@code @Entity} にも紐付かない（＝ドメイン未知）場合は
 * crash させず skip し、skip 件数をログ出力する。マイグレーション専用の中間テーブル等で
 * 対応 Entity が存在しないケースを想定。
 *
 * <h2>凍結（baseline）方式</h2>
 * <p>SQL 走査は ArchUnit のバイトコード解析の<b>外</b>のため、FreezingArchRule は使えない。
 * 代わりに git 管理の baseline テキストファイル
 * {@code src/test/resources/archunit_store/cross_domain_fk_baseline.txt} に現存する
 * クロスドメイン FK を全件記録し、<b>baseline に無い新規 FK が現れたときのみ fail</b> させる。
 * 各違反は次の安定キーで 1 行記録する（ソート安定する形式）:
 * <pre>{@code <子テーブル> -> <親テーブル> [<制約名 or "fk">] (<onDelete>) @<ファイル名>:<行番号>}</pre>
 *
 * <p>撤廃（chip-away）が進み違反が消えたら、baseline から該当行を削除する運用とする。
 * 既存 archunit 番人の refreeze 思想に倣い、baseline からの「漏れ」のみを fail とする
 * （baseline にあるが現在は消えている行は「解消済み」として無視＝fail させない）。
 *
 * <h2>新規 CASCADE / SET NULL の即時阻止</h2>
 * <p>baseline に無い新規 FK が「クロスドメイン<b>かつ</b> {@code ON DELETE CASCADE/SET NULL}」の
 * 場合は、通常のクロスドメイン違反より強いメッセージで fail させる（原則 #2 違反の再混入を
 * 即座に止める）。
 *
 * <h2>baseline の再生成方法</h2>
 * <p>意図的に baseline を作り直したいとき（初回生成 / 大規模リネーム後の再採取）は、
 * システムプロパティまたは環境変数を立てて 1 度だけ実行する:
 * <pre>{@code ./gradlew test --tests "*CrossDomainForeignKeyArchTest" -Darchunit.fk.refreeze=true}</pre>
 * あるいは {@code ARCHUNIT_FK_REFREEZE=true} を環境変数に設定する。
 * 再生成された baseline は必ず差分を目視確認した上で git にコミットすること
 * （骨抜き＝検出ロジックを甘くして緑にするのは禁止。baseline は「現状を正直に記録」する）。
 */
class CrossDomainForeignKeyArchTest {

    /** マイグレーション SQL のルート（worktree からの相対パス）。 */
    private static final Path MIGRATION_DIR =
        Paths.get("src", "main", "resources", "db", "migration");

    /** クロスドメイン FK の baseline。 */
    private static final Path BASELINE_FILE =
        Paths.get("src", "test", "resources", "archunit_store", "cross_domain_fk_baseline.txt");

    /** baseline 再生成スイッチ（システムプロパティ / 環境変数）。 */
    private static final boolean REFREEZE =
        Boolean.getBoolean("archunit.fk.refreeze")
            || "true".equalsIgnoreCase(System.getenv("ARCHUNIT_FK_REFREEZE"));

    // ------------------------------------------------------------------
    // 正規表現
    // ------------------------------------------------------------------

    /** CREATE TABLE / ALTER TABLE の対象テーブル名（任意でバッククォート、スキーマ付き）。 */
    private static final Pattern TABLE_STMT = Pattern.compile(
        "(?is)\\b(?:CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?|ALTER\\s+TABLE)\\s+"
            + "`?(?:\\w+`?\\.`?)?(\\w+)`?");

    /**
     * FOREIGN KEY (...) REFERENCES parent (...) [ON DELETE CASCADE|SET NULL|RESTRICT|NO ACTION]。
     * 改行を跨ぐので DOTALL。制約名は別途 ADD CONSTRAINT から拾う。
     */
    private static final Pattern FK_CLAUSE = Pattern.compile(
        "(?is)FOREIGN\\s+KEY\\s*\\([^)]*\\)\\s*REFERENCES\\s+"
            + "`?(?:\\w+`?\\.`?)?(\\w+)`?\\s*(?:\\([^)]*\\))?"
            + "(?:[^,;()]*?\\bON\\s+DELETE\\s+(CASCADE|SET\\s+NULL|RESTRICT|NO\\s+ACTION))?");

    /** 制約名抽出: 直前の ADD CONSTRAINT <name> / CONSTRAINT <name> FOREIGN KEY。 */
    private static final Pattern CONSTRAINT_NAME = Pattern.compile(
        "(?is)CONSTRAINT\\s+`?(\\w+)`?\\s+FOREIGN\\s+KEY");

    @Test
    void no_new_cross_domain_foreign_keys() throws IOException {
        Path migrationDir = MIGRATION_DIR;
        assertTrue(Files.isDirectory(migrationDir),
            "マイグレーションディレクトリが見つからない: " + migrationDir.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        Map<String, String> tableToDomain = TableDomainResolver.resolve(importMainClasses());

        List<Violation> crossDomainFks = new ArrayList<>();
        int[] skipped = {0};

        List<Path> sqlFiles;
        try (Stream<Path> stream = Files.walk(migrationDir)) {
            sqlFiles = stream
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                .sorted()
                .toList();
        }

        for (Path sql : sqlFiles) {
            scanFile(sql, tableToDomain, crossDomainFks, skipped);
        }

        // 安定ソート（キー文字列の自然順）
        Set<String> currentKeys = new TreeSet<>();
        for (Violation v : crossDomainFks) {
            currentKeys.add(v.key());
        }

        if (REFREEZE) {
            writeBaseline(currentKeys);
            System.out.println("[CrossDomainForeignKeyArchTest] baseline を再生成しました: "
                + currentKeys.size() + " 件 -> " + BASELINE_FILE.toAbsolutePath());
            System.out.println("[CrossDomainForeignKeyArchTest] skip(ドメイン未知)件数: " + skipped[0]);
            return;
        }

        Set<String> baseline = readBaseline();
        System.out.println("[CrossDomainForeignKeyArchTest] 検出クロスドメインFK: "
            + currentKeys.size() + " 件 / baseline: " + baseline.size()
            + " 件 / skip(ドメイン未知): " + skipped[0] + " 件");

        // baseline に無い新規違反のみを抽出
        List<Violation> newViolations = new ArrayList<>();
        for (Violation v : crossDomainFks) {
            if (!baseline.contains(v.key())) {
                newViolations.add(v);
            }
        }

        if (newViolations.isEmpty()) {
            return; // 緑
        }

        // 新規違反のうち CASCADE/SET NULL は強規律で先頭に出す
        StringBuilder sb = new StringBuilder();
        List<Violation> cascadeNew = newViolations.stream()
            .filter(Violation::isCascadeOrSetNull).toList();
        if (!cascadeNew.isEmpty()) {
            sb.append("【最重要】baseline に無い新規クロスドメイン FK で、しかも "
                + "ON DELETE CASCADE / SET NULL が付いています。CLAUDE.md DB設計原則 #2 違反 "
                + "（クロスドメインの削除連鎖は禁止）。即時に撤廃すること:\n");
            for (Violation v : cascadeNew) {
                sb.append("  ✗✗ ").append(v.describe()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("baseline に無い新規クロスドメイン FK を検出しました。"
            + "CLAUDE.md DB設計原則 #1（クロスドメイン FK 禁止・整合性はアプリ層で保証）違反です。\n");
        for (Violation v : newViolations) {
            sb.append("  ✗ ").append(v.describe()).append('\n');
        }
        sb.append('\n')
            .append("対処: 当該 FK を削除（INDEX のみに）するか、設計上どうしても必要なら "
                + "別軍議で承認を得た上で baseline を再生成してください "
                + "(-Darchunit.fk.refreeze=true)。検出を骨抜きにして緑にするのは禁止です。");

        fail(sb.toString());
    }

    // ------------------------------------------------------------------
    // SQL スキャン
    // ------------------------------------------------------------------

    private void scanFile(Path sql,
                          Map<String, String> tableToDomain,
                          List<Violation> out,
                          int[] skipped) {
        String raw;
        try {
            raw = Files.readString(sql, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("SQL 読み込み失敗: " + sql, e);
        }
        // 行コメント（-- ...）を除去（FK が含まれない説明文の誤検出を避ける）。
        String text = stripLineComments(raw);

        String fileName = sql.getFileName().toString();

        // CREATE/ALTER TABLE の出現位置とテーブル名を収集（offset 昇順）。
        List<int[]> tableStmtOffsets = new ArrayList<>();
        List<String> tableStmtNames = new ArrayList<>();
        Matcher tm = TABLE_STMT.matcher(text);
        while (tm.find()) {
            tableStmtOffsets.add(new int[]{tm.start()});
            tableStmtNames.add(tm.group(1));
        }

        Matcher fk = FK_CLAUSE.matcher(text);
        while (fk.find()) {
            int fkStart = fk.start();
            String parent = fk.group(1);
            String onDelete = normalizeOnDelete(fk.group(2));

            // 直近の CREATE/ALTER TABLE を子テーブルとみなす
            String child = enclosingTable(tableStmtOffsets, tableStmtNames, fkStart);
            if (child == null) {
                continue; // テーブル文脈が取れない FK（事実上発生しない）
            }

            String constraint = constraintNameBefore(text, fkStart);
            int line = lineNumberAt(text, fkStart);

            String childDomain = tableToDomain.get(TableDomainResolver.normalize(child));
            String parentDomain = tableToDomain.get(TableDomainResolver.normalize(parent));

            if (childDomain == null || parentDomain == null) {
                skipped[0]++;
                continue; // ドメイン未知 → skip
            }
            if (childDomain.equals(parentDomain)) {
                continue; // 同一ドメインは許容
            }
            if (DomainPackages.isSharedDomain(childDomain)
                    || DomainPackages.isSharedDomain(parentDomain)) {
                continue; // common 基盤との FK は許容
            }

            out.add(new Violation(
                TableDomainResolver.normalize(child),
                TableDomainResolver.normalize(parent),
                constraint,
                onDelete,
                fileName,
                line,
                childDomain,
                parentDomain));
        }
    }

    /** offset 直前の CREATE/ALTER TABLE 対象を返す。 */
    private static String enclosingTable(List<int[]> offsets, List<String> names, int fkStart) {
        String current = null;
        for (int i = 0; i < offsets.size(); i++) {
            if (offsets.get(i)[0] <= fkStart) {
                current = names.get(i);
            } else {
                break;
            }
        }
        return current;
    }

    /** fkStart 直前にある CONSTRAINT 名（同一ステートメント内）を拾う。無ければ "fk"。 */
    private static String constraintNameBefore(String text, int fkStart) {
        // FK 句の直前 200 文字以内で CONSTRAINT <name> FOREIGN KEY を探す
        int lookbackStart = Math.max(0, fkStart - 256);
        String window = text.substring(lookbackStart, Math.min(text.length(), fkStart + 32));
        Matcher m = CONSTRAINT_NAME.matcher(window);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last != null ? last : "fk";
    }

    private static String normalizeOnDelete(String raw) {
        if (raw == null) {
            return "RESTRICT"; // 明示なしは RESTRICT 相当（記録は安定キー目的）
        }
        return raw.replaceAll("\\s+", " ").toUpperCase();
    }

    private static int lineNumberAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** {@code -- } 以降を行末まで除去（文字数・改行は保持してオフセット/行番号を維持）。 */
    private static String stripLineComments(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                // 行末までスペースで置換（改行は残す）
                while (i < n && text.charAt(i) != '\n') {
                    sb.append(' ');
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // baseline 入出力
    // ------------------------------------------------------------------

    private static Set<String> readBaseline() throws IOException {
        if (!Files.exists(BASELINE_FILE)) {
            return Collections.emptySet();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String line : Files.readAllLines(BASELINE_FILE, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            set.add(t);
        }
        return set;
    }

    private static void writeBaseline(Set<String> keys) throws IOException {
        Files.createDirectories(BASELINE_FILE.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# クロスドメイン FK baseline（D-4 / CrossDomainForeignKeyArchTest が管理）");
        lines.add("# 1 行 = 現存する 1 件のクロスドメイン FK。baseline に無い新規 FK のみ fail。");
        lines.add("# 撤廃が進み FK が消えたら該当行を削除すること（chip-away 運用）。");
        lines.add("# 再生成: ./gradlew test --tests \"*CrossDomainForeignKeyArchTest\" "
            + "-Darchunit.fk.refreeze=true");
        lines.addAll(keys); // keys は TreeSet 由来で既にソート済み
        Files.write(BASELINE_FILE, lines, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // クラスローダ
    // ------------------------------------------------------------------

    private static JavaClasses importMainClasses() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.mannschaft.app");
    }

    // ------------------------------------------------------------------
    // 値オブジェクト
    // ------------------------------------------------------------------

    /** 1 件のクロスドメイン FK 違反。 */
    private record Violation(
            String childTable,
            String parentTable,
            String constraint,
            String onDelete,
            String fileName,
            int line,
            String childDomain,
            String parentDomain) {

        /** baseline 安定キー（ソート安定・ファイル間で一意になる形式）。 */
        String key() {
            return childTable + " -> " + parentTable + " [" + constraint + "] ("
                + onDelete + ") @" + fileName + ":" + line;
        }

        boolean isCascadeOrSetNull() {
            return "CASCADE".equals(onDelete) || "SET NULL".equals(onDelete);
        }

        String describe() {
            return key() + "  (子ドメイン='" + childDomain
                + "' ≠ 親ドメイン='" + parentDomain + "')";
        }
    }
}
