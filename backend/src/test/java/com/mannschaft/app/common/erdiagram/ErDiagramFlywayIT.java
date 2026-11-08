package com.mannschaft.app.common.erdiagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * Flyway 全適用済み MySQL の実スキーマから ER 図を生成し、その性質を検証する（CMP-261103-1940）。
 *
 * <p>タグ {@code er-diagram}: 通常の {@code test} / {@code archTest} には含まれず、専用タスク
 * {@code generateErDiagrams}（{@code er.mode=generate}: docs/architecture/er/ を置き換える）・
 * {@code checkErDiagrams}（{@code er.mode=check}: コミット済みと照合する）だけが実行する。
 *
 * <p>Docker 不在時はローカルでは skip し、{@code -Der.requireDocker=true}（CI）では失敗する。
 */
@Tag("er-diagram")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ER 図: Flyway 実スキーマからの生成（CMP-261103-1940）")
class ErDiagramFlywayIT {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_er")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    private boolean started;
    private SchemaSnapshot snapshot;
    private Map<String, String> tableDomains;
    private Map<String, String> files;

    @BeforeAll
    void setUp() throws Exception {
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            docker = false;
        }
        if (!docker) {
            if (Boolean.getBoolean("er.requireDocker")) {
                throw new AssertionError("Docker が利用できない（er.requireDocker=true のため skip せず失敗させる）");
            }
            assumeTrue(false, "Docker が利用できないため ER 図の生成テストを skip する");
        }
        MYSQL.start();
        started = true;
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
        try (Connection con = connect()) {
            snapshot = InformationSchemaReader.read(con);
        }
        tableDomains = EntityTableDomains.scan();
        files = ErDiagramRenderer.render(snapshot, tableDomains);
    }

    @AfterAll
    void tearDown() {
        if (started) {
            MYSQL.stop();
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** information_schema から独立に引いた BASE TABLE 名（flyway_schema_history を除く）。 */
    private Set<String> baseTablesFromDb() throws Exception {
        Set<String> names = new TreeSet<>();
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES"
                     + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        names.remove("flyway_schema_history");
        return names;
    }

    @Test
    @Order(1)
    @DisplayName("AC-1: README とテーブル/ビューを1件以上持つ各ドメインのファイルが生成される")
    void README_とドメインファイルが生成される() {
        assertThat(files).containsKey("README.md");
        Set<String> expectedDomains = new TreeSet<>();
        for (SchemaSnapshot.Table t : snapshot.tables()) {
            expectedDomains.add(tableDomains.getOrDefault(t.name().toLowerCase(), ErDiagramRenderer.UNMAPPED));
        }
        Set<String> expectedFiles = new TreeSet<>();
        expectedDomains.forEach(d -> expectedFiles.add(d + ".md"));
        expectedFiles.add("README.md");
        assertThat(files.keySet()).containsExactlyInAnyOrderElementsOf(expectedFiles);
        assertThat(expectedDomains).as("実スキーマは複数ドメインにまたがる").hasSizeGreaterThan(10);
    }

    @Test
    @Order(2)
    @DisplayName("AC-2: flyway_schema_history を除く全 BASE TABLE の本体定義が全ファイルを通じてちょうど1回現れる")
    void 全テーブルの本体定義がちょうど1回() throws Exception {
        Set<String> expected = baseTablesFromDb();
        // 図の本体定義（スタブは波括弧ブロックを持たない）
        Pattern entityBlock = Pattern.compile("^    (\\w+) \\{$", Pattern.MULTILINE);
        Map<String, Integer> bodyCounts = new TreeMap<>();
        files.values().forEach(c -> {
            Matcher m = entityBlock.matcher(c);
            while (m.find()) {
                bodyCounts.merge(m.group(1), 1, Integer::sum);
            }
        });
        Map<String, Integer> expectedCounts = new TreeMap<>();
        expected.forEach(t -> expectedCounts.put(ErDiagramRenderer.entityId(t), 1));
        assertThat(bodyCounts).as("mermaid 実体の本体定義: 集合と出現回数").isEqualTo(expectedCounts);
        // 列定義表の見出し
        assertThat(ErDiagramRenderer.definitionCounts(files)).as("列定義表の見出し: 集合と出現回数")
                .isEqualTo(new TreeMap<>(expected.stream().collect(
                        java.util.stream.Collectors.toMap(t -> t, t -> 1))));
        assertThat(files.entrySet()).filteredOn(e -> !e.getKey().equals("README.md"))
                .noneMatch(e -> e.getValue().contains("`flyway_schema_history`"));
    }

    @Test
    @Order(3)
    @DisplayName("AC-3: Entity 未紐付けは _unmapped.md に入り README に件数が出る（0件ならファイルを作らない）")
    void 未紐付けの扱い() {
        long unTables = snapshot.tables().stream().filter(t -> !t.view())
                .filter(t -> !tableDomains.containsKey(t.name().toLowerCase())).count();
        long unViews = snapshot.tables().stream().filter(SchemaSnapshot.Table::view)
                .filter(t -> !tableDomains.containsKey(t.name().toLowerCase())).count();
        assertThat(files.containsKey("_unmapped.md")).isEqualTo(unTables + unViews > 0);
        assertThat(files.get("README.md")).contains(
                "Entity 未紐付け（`_unmapped.md`）: テーブル " + unTables + " 件 / ビュー " + unViews + " 件");
        System.out.println("[er-diagram] 未紐付け: テーブル " + unTables + " / ビュー " + unViews);
    }

    @Test
    @Order(4)
    @DisplayName("AC-6: 型が参照先の主キーと一致しない *_by（shedlock.locked_by / user_badges.awarded_by）に線を引かない")
    void 型不一致の反例に線を引かない() {
        for (String[] counter : new String[][] {{"shedlock", "locked_by"}, {"user_badges", "awarded_by"}}) {
            SchemaSnapshot.Table t = snapshot.tables().stream().filter(x -> x.name().equals(counter[0]))
                    .findFirst().orElseThrow(() -> new AssertionError(counter[0] + " が実スキーマに無い"));
            SchemaSnapshot.Column c = t.columns().stream().filter(x -> x.name().equals(counter[1])).findFirst()
                    .orElseThrow();
            SchemaSnapshot.Column usersPk = snapshot.tables().stream().filter(x -> x.name().equals("users"))
                    .findFirst().orElseThrow().columns().stream().filter(x -> x.name().equals("id")).findFirst()
                    .orElseThrow();
            assertThat(c.dataType()).as(counter[0] + "." + counter[1] + " は users.id と型が違う")
                    .isNotEqualTo(usersPk.dataType());
            String all = String.join("\n", files.values());
            assertThat(all.lines().filter(l -> l.contains(" " + counter[0] + " : \"")
                    && l.endsWith(" " + counter[1] + "\"")))
                    .as(counter[0] + "." + counter[1] + " の関係線").isEmpty();
        }
        // 正例: users への参照推定は実スキーマでも点線として現れる
        assertThat(String.join("\n", files.values())).contains("ID参照(FKなし) user_id");
    }

    @Test
    @Order(5)
    @DisplayName("AC-7: scope_type/scope_id を含む多態参照は線を引かず表に列挙される")
    void 多態参照が表に出る() {
        String all = String.join("\n", files.values());
        assertThat(all).contains("## 多態参照（線を引かない）").containsPattern("\\| `[a-z_0-9]+` \\| `scope_type` \\| `scope_id` \\|");
        assertThat(all).doesNotContain("ID参照(FKなし) scope_id");
    }

    @Test
    @Order(6)
    @DisplayName("AC-8: 推定できない *_id が推定不能参照表に列挙される")
    void 推定不能参照が表に出る() {
        assertThat(String.join("\n", files.values())).contains("## 推定不能参照");
    }

    @Test
    @Order(7)
    @DisplayName("AC-10: 同じスキーマからの2回の生成はバイト一致")
    void 二回生成して一致する() throws Exception {
        SchemaSnapshot second;
        try (Connection con = connect()) {
            second = InformationSchemaReader.read(con);
        }
        assertThat(ErDiagramRenderer.render(second, EntityTableDomains.scan())).isEqualTo(files);
    }

    @Test
    @Order(8)
    @DisplayName("AC-12: 全ドメインの mermaid ブロックが 50,000 文字以下")
    void mermaidブロックが上限以下() {
        assertThat(ErDiagramRenderer.oversizedMermaidBlocks(files)).as("上限超過のドメイン").isEmpty();
        int max = 0;
        String maxName = "";
        for (Map.Entry<String, String> e : files.entrySet()) {
            Matcher m = Pattern.compile("```mermaid\n(.*?)\n```", Pattern.DOTALL).matcher(e.getValue());
            if (m.find() && m.group(1).length() > max) {
                max = m.group(1).length();
                maxName = e.getKey();
            }
        }
        System.out.println("[er-diagram] 最大 mermaid ブロック: " + maxName + " " + max + " 文字");
    }

    @Test
    @Order(9)
    @DisplayName("AC-20: 全列定義表が information_schema と一致する（列集合・列順・型・NULL可否・既定値・コメント）")
    void 列定義表がinformation_schemaと一致する() throws Exception {
        Map<String, List<String[]>> actual = new TreeMap<>();
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COLUMN_TYPE, c.IS_NULLABLE, c.COLUMN_DEFAULT,"
                             + " c.COLUMN_COMMENT FROM information_schema.COLUMNS c JOIN information_schema.TABLES t"
                             + " ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME"
                             + " WHERE c.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE'"
                             + " ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rs.getString(1).equals("flyway_schema_history")) {
                    continue;
                }
                actual.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new String[] {
                        rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)});
            }
        }
        Map<String, List<List<String>>> rendered = new TreeMap<>();
        for (String content : files.values()) {
            String current = null;
            for (String line : content.split("\n")) {
                Matcher h = Pattern.compile("^### `([^`]+)`$").matcher(line);
                if (h.matches()) {
                    current = h.group(1);
                    rendered.put(current, new ArrayList<>());
                } else if (line.startsWith("#")) {
                    current = null;
                } else if (current != null && line.startsWith("| `")) {
                    rendered.get(current).add(splitRow(line));
                }
            }
        }
        assertThat(rendered.keySet()).isEqualTo(actual.keySet());
        for (Map.Entry<String, List<String[]>> e : actual.entrySet()) {
            List<List<String>> rows = rendered.get(e.getKey());
            assertThat(rows).as(e.getKey() + " の列数").hasSameSizeAs(e.getValue());
            for (int i = 0; i < rows.size(); i++) {
                List<String> cells = rows.get(i);
                String[] a = e.getValue().get(i);
                String where = e.getKey() + "." + a[0];
                assertThat(cells).as(where + " は5セル（表が壊れていない）").hasSize(5);
                assertThat(cells.get(0)).as(where + " 列名/列順").isEqualTo("`" + a[0] + "`");
                assertThat(cells.get(1)).as(where + " 型").contains(a[1].replace("|", "\\|"));
                assertThat(cells.get(2)).as(where + " NULL可否").isEqualTo("YES".equals(a[2]) ? "YES" : "NO");
                if (a[3] == null) {
                    assertThat(cells.get(3)).as(where + " 既定値（NULL既定/既定なしの区別）")
                            .startsWith("YES".equals(a[2]) ? "NULL" : "（なし）");
                } else if (!a[3].isEmpty()) {
                    assertThat(cells.get(3)).as(where + " 既定値").contains(a[3].replace("|", "\\|"));
                }
                String expectedComment = a[4] == null ? "" : escapeIndependently(a[4]);
                assertThat(cells.get(4)).as(where + " コメント").isEqualTo(expectedComment);
            }
        }
    }

    /** 本体とは別実装のコメントエスケープ（期待値）。 */
    private static String escapeIndependently(String s) {
        StringBuilder sb = new StringBuilder();
        String norm = s.replace("\r\n", "\n").replace("\r", "\n");
        for (char ch : norm.toCharArray()) {
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '|' -> sb.append("\\|");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\n' -> sb.append("<br>");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** `| a | b |` を、エスケープされていない | で分割する。 */
    private static List<String> splitRow(String row) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 1; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch == '\\' && i + 1 < row.length()) {
                cur.append(ch).append(row.charAt(++i));
            } else if (ch == '|') {
                cells.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        return cells;
    }

    @Test
    @Order(100)
    @DisplayName("AC-13/16: er.mode=generate なら置き換え書き出し、er.mode=check ならコミット済みと照合する")
    void 書き出しまたは照合() {
        String mode = System.getProperty("er.mode", "");
        assumeTrue(mode.equals("generate") || mode.equals("check"),
                "er.mode=generate|check のときだけ実行する（generateErDiagrams / checkErDiagrams）");
        String outputDir = System.getProperty("er.outputDir");
        assertThat(outputDir).as("er.outputDir").isNotBlank();
        Path dir = Path.of(outputDir);
        assertThat(ErDiagramRenderer.oversizedMermaidBlocks(files)).isEmpty();
        if (mode.equals("generate")) {
            ErDiagramFiles.replaceDirectory(dir, files);
            System.out.println("[er-diagram] 生成: " + files.size() + " ファイル → " + dir);
        } else {
            List<String> problems = ErDiagramFiles.diff(dir, files);
            assertThat(problems).as(ErDiagramFiles.driftMessage(problems)).isEmpty();
        }
    }
}
