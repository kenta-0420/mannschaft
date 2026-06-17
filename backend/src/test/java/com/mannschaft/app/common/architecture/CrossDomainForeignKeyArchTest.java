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
 * <h2>凍結（baseline）方式</h2>
 * <p>SQL 走査は ArchUnit のバイトコード解析の<b>外</b>のため、FreezingArchRule は使えない。
 * 代わりに git 管理の baseline テキストファイル
 * {@code src/test/resources/archunit_store/cross_domain_fk_baseline.txt} に net-active な
 * クロスドメイン FK を全件記録し、<b>baseline に無い新規 FK が現れたときのみ fail</b> させる。
 * 各違反は次の安定キーで 1 行記録する（ソート安定する形式）:
 * <pre>{@code <子テーブル> -> <親テーブル> [<制約名 or 無名キー>] (<onDelete>) @<ファイル名>:<行番号>}</pre>
 *
 * <p>撤廃（chip-away）が進み net-active から FK が消えると、再凍結で baseline 行も消える。
 * baseline が空になればクロスドメイン FK 全廃が達成されたことになり、その時点で本番人を
 * 通常ルール（1 件でも検出したら fail）へ格上げできる。
 *
 * <h2>新規 CASCADE / SET NULL の即時阻止</h2>
 * <p>baseline に無い新規 FK が「クロスドメイン<b>かつ</b> {@code ON DELETE CASCADE/SET NULL}」の
 * 場合は、通常のクロスドメイン違反より強いメッセージで fail させる（原則 #2 違反の再混入を
 * 即座に止める）。
 *
 * <h2>baseline の再生成方法</h2>
 * <p>意図的に baseline を作り直したいとき（初回生成 / 撤廃 wave 後の再採取）は、
 * 次のいずれかで 1 度だけ実行する:
 * <pre>{@code ./gradlew test --tests "*CrossDomainForeignKeyArchTest" -Parchunit.fk.refreeze=true}</pre>
 * あるいは環境変数 {@code ARCHUNIT_FK_REFREEZE=true} を設定する。
 * （{@code -P}（Gradle プロジェクトプロパティ）は {@code build.gradle.kts} の
 * {@code tasks.withType<Test>} がテスト JVM の system property
 * {@code archunit.fk.refreeze} へ伝播する。{@code -D} は Gradle 自身の JVM に渡るだけで
 * fork されたテスト JVM には届かないため使わないこと。）
 * 再生成された baseline は必ず差分を目視確認した上で git にコミットすること
 * （骨抜き＝検出ロジックを甘くして緑にするのは禁止。baseline は「net-active を正直に記録」する）。
 */
class CrossDomainForeignKeyArchTest {

    /** マイグレーション SQL のルート（worktree からの相対パス）。 */
    private static final Path MIGRATION_DIR =
        Paths.get("src", "main", "resources", "db", "migration");

    /** クロスドメイン FK の baseline。 */
    private static final Path BASELINE_FILE =
        Paths.get("src", "test", "resources", "archunit_store", "cross_domain_fk_baseline.txt");

    /** baseline 再生成スイッチ（system property は build が {@code -P} から伝播 / 環境変数も可）。 */
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

        // 安定ソート（キー文字列の自然順）
        Set<String> currentKeys = new TreeSet<>();
        for (Violation v : crossDomainFks) {
            currentKeys.add(v.key());
        }

        if (REFREEZE) {
            writeBaseline(currentKeys);
            System.out.println("[CrossDomainForeignKeyArchTest] baseline を再生成しました: "
                + currentKeys.size() + " 件 -> " + BASELINE_FILE.toAbsolutePath());
            System.out.println("[CrossDomainForeignKeyArchTest] net-active FK 総数: "
                + netActive.size() + " 件 / skip(ドメイン未知): " + skipped + " 件");
            return;
        }

        Set<String> baseline = readBaseline();
        System.out.println("[CrossDomainForeignKeyArchTest] net-active クロスドメインFK: "
            + currentKeys.size() + " 件 / baseline: " + baseline.size()
            + " 件 / net-active FK 総数: " + netActive.size()
            + " 件 / skip(ドメイン未知): " + skipped + " 件");

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
                + "(-Parchunit.fk.refreeze=true)。検出を骨抜きにして緑にするのは禁止です。");

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
        lines.add("# 1 行 = 全 migration を Flyway 適用順に再生した後 net-active な 1 件の "
            + "クロスドメイン FK。baseline に無い新規 FK のみ fail。");
        lines.add("# 撤廃 wave が DROP FOREIGN KEY すると net-active から消え、再凍結で該当行も消える"
            + "（chip-away 運用）。baseline が空になればクロスドメイン FK 全廃。");
        lines.add("# 再生成: ./gradlew test --tests \"*CrossDomainForeignKeyArchTest\" "
            + "-Parchunit.fk.refreeze=true （または環境変数 ARCHUNIT_FK_REFREEZE=true）");
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
}
