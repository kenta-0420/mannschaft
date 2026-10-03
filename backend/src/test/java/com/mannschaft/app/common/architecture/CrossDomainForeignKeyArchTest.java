package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.migration.SqlTextScanningUtils;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * 単に FK 宣言を累積カウントするのではなく、<b>全 migration を Flyway バージョン順に適用した
 * あとに DB へ実在する FK（net-active 集合）</b>を状態機械で再生する。すなわち:
 * <ul>
 *   <li>(a) ADD: {@code CREATE TABLE} インラインの
 *       {@code FOREIGN KEY (...) REFERENCES <parent> (...)} および
 *       {@code ALTER TABLE <child> ADD CONSTRAINT ... FOREIGN KEY (...) REFERENCES <parent> (...)}
 *       を net 集合に追加する。同一 FK 句に付く {@code ON DELETE CASCADE|SET NULL} も同時に捕捉する。</li>
 *   <li>(b) DROP: {@code ALTER TABLE <child> DROP FOREIGN KEY <name>} /
 *       {@code DROP CONSTRAINT <name>} は net 集合から該当キーを除去し、
 *       {@code DROP TABLE [IF EXISTS] <child>} は子テーブルが {@code <child>} の全 FK を除去する。</li>
 * </ul>
 * これにより、撤廃 wave（V62.001〜 等）で {@code DROP FOREIGN KEY} された FK は net 集合から
 * 消え、撤廃が進めば baseline も自然に縮む（chip-away）。
 *
 * <h2>Flyway バージョン順の走査（必須）</h2>
 * <p>ADD→DROP の最終状態を正しく再生するには適用順＝Flyway バージョン昇順で走査しなければ
 * ならない。単純な文字列ソートでは {@code "V10.011"} が {@code "V2.017"} より前に来てしまい
 * 適用順と逆転する。本テストはファイル名から {@code V<major>.<minor>[.<...>]} の数値成分を
 * 解析し、成分ごとの数値比較で昇順ソートする（{@code V9.091.1} のような 3 成分版にも対応）。
 * Repeatable migration（{@code R__...}）は全 versioned migration の<b>後</b>に走らせる
 * （Flyway の適用順に一致）。Flyway の規約（{@code __} ＝バージョンと説明の区切り）に
 * 合致しないファイル（{@code rollback/} 配下の {@code *_rollback.sql} 等）は適用対象では
 * ないため走査から除外する。
 *
 * <h2>net 集合のキー設計（名寄せの堅牢性）</h2>
 * <ul>
 *   <li><b>名前付き FK</b>: {@code (子テーブル, 制約名)} をキーにする。これにより
 *       {@code DROP FOREIGN KEY <name>} で正しく除去でき、同一制約の DROP→再 ADD
 *       （例 V10.022 の付け替え）も追える。</li>
 *   <li><b>無名 FK</b>（{@code CONSTRAINT} 名なし）: 固定文字列だとキー衝突するため
 *       {@code (子テーブル, 親テーブル, FK 対象カラム列)} を代替キーにする。カラムが
 *       取れない場合は {@code (子, 親)} ＋出現順インデックスでベストエフォート化する。
 *       無名 FK は {@code DROP FOREIGN KEY} で名指し除去されないことが多い（実 DB の
 *       自動採番名は migration テキストからは追えない＝残ってよい）。ただし
 *       {@code DROP TABLE} では子テーブルごと消える。</li>
 * </ul>
 *
 * <h2>ドメイン未知のスキップ</h2>
 * <p>子・親いずれかのテーブルがどの {@code @Entity} にも紐付かない（＝ドメイン未知）場合は
 * crash させず skip し、skip 件数をログ出力する。マイグレーション専用の中間テーブル等で
 * 対応 Entity が存在しないケースを想定。
 *
 * <h2>恒久ルール方式（全廃達成後の格上げ）</h2>
 * <p>クロスドメイン FK 撤廃キャンペーンで <b>baseline 158 → 0 件</b> を達成した。これに伴い、本番人は
 * かつての baseline 凍結（chip-away）方式から、<b>net-active なクロスドメイン FK を 1 件でも検出したら
 * fail する恒久 ArchRule</b> へ格上げした（baseline テキストファイルおよび再凍結スイッチ
 * {@code archunit.fk.refreeze} は廃止）。SQL 走査は ArchUnit のバイトコード解析の<b>外</b>のため
 * FreezingArchRule は使えず、migration を Flyway 適用順に再生して net-active FK 集合を自前で算出し、
 * ドメイン解決（同一ドメイン・共有ドメイン common は許容）の後、残ったクロスドメイン FK が
 * 0 件であることを検証する。各違反は次の形式で列挙する:
 * <pre>{@code <子テーブル> -> <親テーブル> [<制約名 or 無名キー>] (<onDelete>) @<ファイル名>:<行番号>}</pre>
 *
 * <h2>CASCADE / SET NULL の即時阻止</h2>
 * <p>検出したクロスドメイン FK が {@code ON DELETE CASCADE/SET NULL} の場合は、通常のクロスドメイン
 * 違反より強いメッセージで fail させる（原則 #2 = クロスドメイン削除連鎖の禁止の再混入を即座に止める）。
 *
 * <h2>新規クロスドメイン FK が必要になった場合</h2>
 * <p>本ルールは CLAUDE.md DB設計原則 #1（クロスドメイン FK は作らない・整合性はアプリ層で保証）の
 * 恒久 enforcement である。設計上どうしても他ドメインのテーブルを参照したい場合は、FK を張らず INDEX のみ
 * とし参照整合性をアプリ層で保証するか、参照先を共有ドメイン(common)へ寄せる / {@code DomainPackages} の
 * ドメイン定義を見直すことで対応する。検出を骨抜きにして緑化する（ルールを甘くする）のは禁止。
 */
class CrossDomainForeignKeyArchTest {

    /** マイグレーション SQL のルート（worktree からの相対パス）。 */
    private static final Path MIGRATION_DIR =
        Paths.get("src", "main", "resources", "db", "migration");

    // ------------------------------------------------------------------
    // 正規表現
    // ------------------------------------------------------------------

    /** CREATE TABLE / ALTER TABLE の対象テーブル名（任意でバッククォート、スキーマ付き）。 */
    private static final Pattern TABLE_STMT = Pattern.compile(
        "(?is)\\b(?:CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?|ALTER\\s+TABLE)\\s+"
            + "`?(?:\\w+`?\\.`?)?(\\w+)`?");

    /**
     * FOREIGN KEY (cols) REFERENCES parent (...) [ON DELETE CASCADE|SET NULL|RESTRICT|NO ACTION]。
     * 改行を跨ぐので DOTALL。group(1)=FK 対象カラム列, group(2)=親テーブル, group(3)=onDelete。
     * 制約名は別途 ADD CONSTRAINT から拾う。
     */
    private static final Pattern FK_CLAUSE = Pattern.compile(
        "(?is)FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+"
            + "`?(?:\\w+`?\\.`?)?(\\w+)`?\\s*(?:\\([^)]*\\))?"
            + "(?:[^,;()]*?\\bON\\s+DELETE\\s+(CASCADE|SET\\s+NULL|RESTRICT|NO\\s+ACTION))?");

    /** 制約名抽出: 直前の ADD CONSTRAINT <name> / CONSTRAINT <name> FOREIGN KEY。 */
    private static final Pattern CONSTRAINT_NAME = Pattern.compile(
        "(?is)CONSTRAINT\\s+`?(\\w+)`?\\s+FOREIGN\\s+KEY");

    /** ALTER TABLE <child> DROP FOREIGN KEY <name> / DROP CONSTRAINT <name>。 */
    private static final Pattern DROP_CONSTRAINT_STMT = Pattern.compile(
        "(?is)\\bDROP\\s+(?:FOREIGN\\s+KEY|CONSTRAINT)\\s+`?(\\w+)`?");

    /** DROP TABLE [IF EXISTS] <table>。 */
    private static final Pattern DROP_TABLE_STMT = Pattern.compile(
        "(?is)\\bDROP\\s+TABLE(?:\\s+IF\\s+EXISTS)?\\s+`?(?:\\w+`?\\.`?)?(\\w+)`?");

    @Test
    void no_new_cross_domain_foreign_keys() throws IOException {
        Path migrationDir = MIGRATION_DIR;
        assertTrue(Files.isDirectory(migrationDir),
            "マイグレーションディレクトリが見つからない: " + migrationDir.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        Map<String, String> tableToDomain = TableDomainResolver.resolve(importMainClasses());

        // Flyway 適用順にソートした migration ファイルを取得。
        List<Path> orderedSqlFiles = collectMigrationsInFlywayOrder(migrationDir);

        // 全 migration を順に再生し、net-active な FK 集合を構築する。
        // キー = FkKey（名前付き or 無名）、値 = FkRecord（実在する 1 件の FK）。
        Map<FkKey, FkRecord> netActive = new LinkedHashMap<>();
        for (Path sql : orderedSqlFiles) {
            replayFile(sql, netActive);
        }

        // net-active のうちクロスドメイン分のみを違反として収集。
        List<Violation> crossDomainFks = new ArrayList<>();
        int skipped = 0;
        for (FkRecord rec : netActive.values()) {
            String childDomain = tableToDomain.get(TableDomainResolver.normalize(rec.childTable));
            String parentDomain = tableToDomain.get(TableDomainResolver.normalize(rec.parentTable));

            if (childDomain == null || parentDomain == null) {
                skipped++;
                continue; // ドメイン未知 → skip
            }
            if (childDomain.equals(parentDomain)) {
                continue; // 同一ドメインは許容
            }
            if (DomainPackages.isSharedDomain(childDomain)
                    || DomainPackages.isSharedDomain(parentDomain)) {
                continue; // common 基盤との FK は許容
            }

            crossDomainFks.add(new Violation(
                TableDomainResolver.normalize(rec.childTable),
                TableDomainResolver.normalize(rec.parentTable),
                rec.constraintLabel(),
                rec.onDelete,
                rec.fileName,
                rec.line,
                childDomain,
                parentDomain));
        }

        // 恒久ルール（クロスドメインFK全廃 158→0 達成に伴う baseline 方式からの格上げ）:
        // net-active なクロスドメイン FK が 1 件でも在れば fail させる。検出ロジック（migration 再生→
        // ドメイン解決→同一/shared 除外）は baseline 方式時代から不変で、判定だけを「0 件必須」に強化した。
        System.out.println("[CrossDomainForeignKeyArchTest] net-active クロスドメインFK: "
            + crossDomainFks.size() + " 件（恒久ルール: 0 件必須）"
            + " / net-active FK 総数: " + netActive.size()
            + " 件 / skip(ドメイン未知): " + skipped + " 件");

        if (crossDomainFks.isEmpty()) {
            return; // 緑（クロスドメイン FK 0 件 = 原則 #1 を満たす）
        }

        // CASCADE/SET NULL は強規律で先頭に出す
        StringBuilder sb = new StringBuilder();
        List<Violation> cascade = crossDomainFks.stream()
            .filter(Violation::isCascadeOrSetNull).toList();
        if (!cascade.isEmpty()) {
            sb.append("【最重要】クロスドメイン FK で、しかも "
                + "ON DELETE CASCADE / SET NULL が付いています。CLAUDE.md DB設計原則 #2 違反 "
                + "（クロスドメインの削除連鎖は禁止）。即時に撤廃すること:\n");
            for (Violation v : cascade) {
                sb.append("  ✗✗ ").append(v.describe()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("クロスドメイン FK を検出しました。"
            + "CLAUDE.md DB設計原則 #1（クロスドメイン FK 禁止・整合性はアプリ層で保証）違反です。\n"
            + "本キャンペーンで全廃済（baseline 158→0）であり、新規のクロスドメイン FK は許容されません。\n");
        for (Violation v : crossDomainFks) {
            sb.append("  ✗ ").append(v.describe()).append('\n');
        }
        sb.append('\n')
            .append("対処: 当該 FK を削除（INDEX のみに）し、参照整合性はアプリ層で保証すること。"
                + "設計上どうしても必要なら、共有ドメイン(common)への配置や DomainPackages の見直しで対応し、"
                + "安易に本ルールを緩めない（検出を骨抜きにして緑化するのは禁止）。");

        fail(sb.toString());
    }

    // ------------------------------------------------------------------
    // migration ファイル収集（Flyway 適用順）
    // ------------------------------------------------------------------

    /**
     * migration ディレクトリ配下の適用対象 SQL を Flyway 適用順で返す。
     *
     * <p>versioned migration（{@code V<ver>__...}）→ バージョン数値成分の昇順、
     * repeatable migration（{@code R__...}）→ 全 versioned の後（ファイル名昇順）。
     * Flyway 規約に合致しないファイル（{@code rollback/} 配下の {@code *_rollback.sql} 等、
     * {@code __} を含まないもの）は適用されないため除外する。
     */
    private static List<Path> collectMigrationsInFlywayOrder(Path migrationDir) throws IOException {
        List<MigrationFile> versioned = new ArrayList<>();
        List<MigrationFile> repeatable = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(migrationDir)) {
            stream
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    if (!name.contains("__")) {
                        return; // Flyway 適用対象でない（rollback 等）→ 除外
                    }
                    if (name.charAt(0) == 'R' || name.charAt(0) == 'r') {
                        repeatable.add(new MigrationFile(p, name, null));
                        return;
                    }
                    long[] version = parseVersion(name);
                    if (version == null) {
                        return; // V でも R でもない異形は適用対象外として除外
                    }
                    versioned.add(new MigrationFile(p, name, version));
                });
        }

        versioned.sort(Comparator
            .comparing((MigrationFile mf) -> mf.version, CrossDomainForeignKeyArchTest::compareVersion)
            .thenComparing(mf -> mf.name));
        repeatable.sort(Comparator.comparing(mf -> mf.name));

        List<Path> ordered = new ArrayList<>(versioned.size() + repeatable.size());
        for (MigrationFile mf : versioned) {
            ordered.add(mf.path);
        }
        for (MigrationFile mf : repeatable) {
            ordered.add(mf.path);
        }
        return ordered;
    }

    /**
     * ファイル名 {@code V<n>(.<n>)*__...sql} からバージョン数値成分配列を解析する。
     * Flyway はバージョン内の {@code .} と {@code _}（ただし区切りの {@code __} を除く）を
     * 成分セパレータとして扱うが、本リポジトリの版番号は {@code .} 区切りで統一されているため
     * {@code .} のみで分割する。解析できない場合は null を返す。
     */
    private static long[] parseVersion(String fileName) {
        if (fileName.length() < 2 || (fileName.charAt(0) != 'V' && fileName.charAt(0) != 'v')) {
            return null;
        }
        int sep = fileName.indexOf("__");
        if (sep < 0) {
            return null;
        }
        String versionPart = fileName.substring(1, sep); // 先頭 'V' を除く
        String[] comps = versionPart.split("\\.");
        long[] nums = new long[comps.length];
        for (int i = 0; i < comps.length; i++) {
            try {
                nums[i] = Long.parseLong(comps[i].trim());
            } catch (NumberFormatException e) {
                return null; // 数値でない成分が混じる版は対象外
            }
        }
        return nums;
    }

    /** バージョン数値成分配列を成分ごとに比較する（短い方は 0 埋め相当）。 */
    private static int compareVersion(long[] a, long[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            long ai = i < a.length ? a[i] : 0L;
            long bi = i < b.length ? b[i] : 0L;
            int cmp = Long.compare(ai, bi);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** 収集対象の 1 migration ファイル。 */
    private record MigrationFile(Path path, String name, long[] version) {
    }

    // ------------------------------------------------------------------
    // SQL 再生（ADD / DROP イベントで net 集合を更新）
    // ------------------------------------------------------------------

    private void replayFile(Path sql, Map<FkKey, FkRecord> netActive) {
        String raw;
        try {
            raw = Files.readString(sql, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("SQL 読み込み失敗: " + sql, e);
        }
        replayText(raw, sql.getFileName().toString(), netActive);
    }

    /**
     * SQL テキストを再生し {@code netActive} に反映する（ファイル I/O を伴わない）。
     * 回帰ガードテスト（{@code ScanningRegressionTest}・本クラスの {@code @Nested}）から
     * フィクスチャ文字列を直接流し込めるように、ファイル読み込みと分離してある。
     */
    private static void replayText(String raw, String fileName, Map<FkKey, FkRecord> netActive) {
        // コメント（行コメント -- と ブロックコメント /* */ の両方）を除去する。
        // ブロックコメントを除去しないと、説明コメント中に書かれた
        // 「FOREIGN KEY (...) REFERENCES ...」のような例示テキストや
        // CREATE/ALTER TABLE への言及を誤って本文として拾ってしまう
        // （MigrationPrimaryKeyConventionTest で実際に起きた誤検知と同型）。
        String text = SqlTextScanningUtils.stripComments(raw);
        // CREATE TEMPORARY TABLE の本体を空白化して走査対象から除外する。
        // TABLE_STMT は CREATE TEMPORARY TABLE を認識しないため、除外しないと
        // 一時表の内容が直前の実テーブルの enclosingTable に紛れ込み、
        // 一時表内の FK/DROP TABLE を別の実テーブルのものとして誤帰属する。
        text = SqlTextScanningUtils.blankOutTemporaryTables(text);

        // CREATE/ALTER TABLE の出現位置とテーブル名を収集（offset 昇順）。
        List<int[]> tableStmtOffsets = new ArrayList<>();
        List<String> tableStmtNames = new ArrayList<>();
        Matcher tm = TABLE_STMT.matcher(text);
        while (tm.find()) {
            tableStmtOffsets.add(new int[]{tm.start()});
            tableStmtNames.add(tm.group(1));
        }

        // 同一ファイル内のイベントを offset 昇順で適用するため、まず全イベントを収集する。
        List<Event> events = new ArrayList<>();

        Matcher fk = FK_CLAUSE.matcher(text);
        while (fk.find()) {
            int fkStart = fk.start();
            String fkColumns = fk.group(1);
            String parent = fk.group(2);
            String onDelete = normalizeOnDelete(fk.group(3));
            String child = enclosingTable(tableStmtOffsets, tableStmtNames, fkStart);
            if (child == null) {
                continue; // テーブル文脈が取れない FK（事実上発生しない）
            }
            String constraint = constraintNameBefore(text, fkStart);
            int line = lineNumberAt(text, fkStart);
            events.add(Event.add(fkStart, child, parent, constraint, fkColumns, onDelete, fileName, line));
        }

        Matcher dropC = DROP_CONSTRAINT_STMT.matcher(text);
        while (dropC.find()) {
            int dropStart = dropC.start();
            String constraintName = dropC.group(1);
            String child = enclosingTable(tableStmtOffsets, tableStmtNames, dropStart);
            if (child == null) {
                continue;
            }
            events.add(Event.dropConstraint(dropStart, child, constraintName));
        }

        Matcher dropT = DROP_TABLE_STMT.matcher(text);
        while (dropT.find()) {
            events.add(Event.dropTable(dropT.start(), dropT.group(1)));
        }

        // offset 昇順で適用（同一ファイル内の DROP→再 ADD を正しく順序付ける）。
        events.sort(Comparator.comparingInt(e -> e.offset));

        // 無名 FK の出現順インデックス（ファイル横断で衝突を避けるため child+parent 単位）。
        Map<String, Integer> anonSeq = new LinkedHashMap<>();

        for (Event e : events) {
            switch (e.type) {
                case ADD -> {
                    String normChild = TableDomainResolver.normalize(e.child);
                    String normParent = TableDomainResolver.normalize(e.parent);
                    FkKey key;
                    if (e.constraint != null) {
                        key = FkKey.named(normChild, e.constraint.toLowerCase());
                    } else {
                        String cols = normalizeColumns(e.columns);
                        if (cols.isEmpty()) {
                            // カラムも取れない → (child, parent) ＋出現順でベストエフォート。
                            String seqKey = normChild + "|" + normParent;
                            int idx = anonSeq.merge(seqKey, 1, Integer::sum);
                            cols = "#" + idx;
                        }
                        key = FkKey.anonymous(normChild, normParent, cols);
                    }
                    netActive.put(key, new FkRecord(
                        e.child, e.parent, e.constraint, e.onDelete, e.fileName, e.line));
                }
                case DROP_CONSTRAINT -> {
                    String normChild = TableDomainResolver.normalize(e.child);
                    netActive.remove(FkKey.named(normChild, e.constraint.toLowerCase()));
                }
                case DROP_TABLE -> {
                    String normChild = TableDomainResolver.normalize(e.child);
                    netActive.keySet().removeIf(k -> k.childTable.equals(normChild));
                }
                default -> throw new IllegalStateException("未知のイベント種別: " + e.type);
            }
        }
    }

    /** offset 直前の CREATE/ALTER TABLE 対象を返す。 */
    private static String enclosingTable(List<int[]> offsets, List<String> names, int stmtStart) {
        String current = null;
        for (int i = 0; i < offsets.size(); i++) {
            if (offsets.get(i)[0] <= stmtStart) {
                current = names.get(i);
            } else {
                break;
            }
        }
        return current;
    }

    /** stmtStart 直前にある CONSTRAINT 名（同一ステートメント内）を拾う。無ければ null。 */
    private static String constraintNameBefore(String text, int fkStart) {
        // FK 句の直前 256 文字以内で CONSTRAINT <name> FOREIGN KEY を探す
        int lookbackStart = Math.max(0, fkStart - 256);
        String window = text.substring(lookbackStart, Math.min(text.length(), fkStart + 32));
        Matcher m = CONSTRAINT_NAME.matcher(window);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private static String normalizeOnDelete(String raw) {
        if (raw == null) {
            return "RESTRICT"; // 明示なしは RESTRICT 相当（記録は安定キー目的）
        }
        return raw.replaceAll("\\s+", " ").toUpperCase();
    }

    /** FK 対象カラム列を比較用に正規化する（空白除去・小文字化・バッククォート除去）。 */
    private static String normalizeColumns(String columns) {
        if (columns == null) {
            return "";
        }
        return columns.replace("`", "").replaceAll("\\s+", "").toLowerCase();
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

    // SQL コメント除去・引用符スキップ・一時表除外・文末検出は SqlTextScanningUtils
    // （common.migration パッケージ）に共通化してある（CMP-022: 番人ごとに不統一だった
    // 前処理ロジックの一本化。「何を違反とするか」の判定はこのクラス固有のまま残す）。

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

    /** net-active 集合のキー。名前付き FK は (child, constraint)、無名 FK は (child, parent, columns)。 */
    private record FkKey(String childTable, String parentTable, String discriminator) {

        static FkKey named(String child, String constraintLower) {
            // parentTable は null（名前付きキーは制約名で一意）。
            return new FkKey(child, null, "name:" + constraintLower);
        }

        static FkKey anonymous(String child, String parent, String columnsOrSeq) {
            return new FkKey(child, parent, "anon:" + columnsOrSeq);
        }
    }

    /** net-active な 1 件の FK の付随情報（最後に ADD された時点の情報を保持）。 */
    private record FkRecord(
            String childTable,
            String parentTable,
            String constraint,
            String onDelete,
            String fileName,
            int line) {

        /** baseline 表示用の制約ラベル（無名は "fk"）。 */
        String constraintLabel() {
            return constraint != null ? constraint : "fk";
        }
    }

    /** SQL 再生イベント（ADD / DROP CONSTRAINT / DROP TABLE）。 */
    private record Event(
            EventType type,
            int offset,
            String child,
            String parent,
            String constraint,
            String columns,
            String onDelete,
            String fileName,
            int line) {

        static Event add(int offset, String child, String parent, String constraint,
                         String columns, String onDelete, String fileName, int line) {
            return new Event(EventType.ADD, offset, child, parent, constraint, columns,
                onDelete, fileName, line);
        }

        static Event dropConstraint(int offset, String child, String constraint) {
            return new Event(EventType.DROP_CONSTRAINT, offset, child, null, constraint,
                null, null, null, 0);
        }

        static Event dropTable(int offset, String table) {
            return new Event(EventType.DROP_TABLE, offset, table, null, null, null, null, null, 0);
        }
    }

    private enum EventType {
        ADD, DROP_CONSTRAINT, DROP_TABLE
    }

    /** 1 件のクロスドメイン FK 違反（baseline 行に対応）。 */
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

    // ------------------------------------------------------------------
    // 走査ロジックの回帰ガード（CMP-022 / issue #2589 で実測された欠陥）
    // ------------------------------------------------------------------

    /**
     * SQL 走査ロジック自体（コメント除去・一時表除外）の回帰ガード。
     *
     * <p>{@code MigrationPrimaryKeyConventionTest} で実測された 2 欠陥
     * （説明コメントを本文として誤検出／{@code CREATE TEMPORARY TABLE} 未対応による誤帰属）が
     * 本クラスにも同型で存在した（issue #2589 系フォローアップ・CMP-022）。
     * 実ファイルではなくフィクスチャ文字列を直接 {@code replayText} に流し込み、
     * 将来パーサを触った際にこれらの穴が再発したら落ちるようにする。</p>
     */
    @Nested
    class ScanningRegressionTest {

        @Test
        @DisplayName("ブロックコメント中のFK/CREATE TABLE言及は本文として検出されない")
        void blockCommentIsNotScannedAsBody() {
            String sql = "/* サンプル: FOREIGN KEY (child_id) REFERENCES parent_table (id)"
                + " ON DELETE CASCADE を CREATE TABLE で書く例 */\n"
                + "CREATE TABLE only_table_a (\n"
                + "  id BINARY(16) PRIMARY KEY\n"
                + ");\n";
            Map<FkKey, FkRecord> netActive = new LinkedHashMap<>();
            replayText(sql, "fixture_block_comment.sql", netActive);

            assertTrue(netActive.isEmpty(),
                "ブロックコメント内の FOREIGN KEY 例示テキストが FK として検出された:"
                    + " " + netActive.values());
        }

        @Test
        @DisplayName("CREATE TEMPORARY TABLE内のFK/DROP TABLEは直前の実テーブルに誤帰属しない")
        void temporaryTableIsNotMisattributedToPrecedingRealTable() {
            String sql = "CREATE TABLE real_child (\n"
                + "  id BINARY(16) PRIMARY KEY,\n"
                + "  real_parent_id BINARY(16),\n"
                + "  CONSTRAINT fk_real FOREIGN KEY (real_parent_id) REFERENCES real_parent (id)\n"
                + ");\n"
                + "CREATE TEMPORARY TABLE tmp_scratch (\n"
                + "  id BINARY(16),\n"
                + "  FOREIGN KEY (other_id) REFERENCES temp_only_parent (id)\n"
                + ");\n"
                + "DROP TEMPORARY TABLE IF EXISTS tmp_scratch;\n";
            Map<FkKey, FkRecord> netActive = new LinkedHashMap<>();
            replayText(sql, "fixture_temp_table.sql", netActive);

            boolean hasRealFk = netActive.values().stream()
                .anyMatch(r -> "real_child".equals(r.childTable())
                    && "real_parent".equals(r.parentTable()));
            assertTrue(hasRealFk, "実テーブルの FK が消えている: " + netActive.values());

            boolean leakedTempFk = netActive.values().stream()
                .anyMatch(r -> "temp_only_parent".equals(r.parentTable())
                    || "tmp_scratch".equals(r.childTable()));
            assertTrue(!leakedTempFk,
                "一時表内の FK が実テーブル側へ誤帰属している: " + netActive.values());
        }
    }
}
