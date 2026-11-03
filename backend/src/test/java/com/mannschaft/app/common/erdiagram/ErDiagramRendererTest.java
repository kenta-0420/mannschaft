package com.mannschaft.app.common.erdiagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ER 図生成の純ロジック（スキーマを模した入力）の単体テスト（CMP-261103-1940）。
 * Docker を要さないため通常の {@code test} で走る。Flyway 実スキーマ依存の検証は {@link ErDiagramFlywayIT}。
 */
@DisplayName("ER 図生成: 純ロジック単体テスト（CMP-261103-1940）")
class ErDiagramRendererTest {

    // ─── 入力の組み立て ───

    private static SchemaSnapshot.Column col(String name, String type, boolean nullable) {
        return new SchemaSnapshot.Column(name, type, type, nullable, null, "", "");
    }

    private static SchemaSnapshot.Table table(String name, List<SchemaSnapshot.Column> cols, List<String> pk,
                                              List<String> uk) {
        return new SchemaSnapshot.Table(name, false, cols, pk, uk);
    }

    private static SchemaSnapshot.Table view(String name) {
        return new SchemaSnapshot.Table(name, true, List.of(col("x", "int", true)), List.of(), List.of());
    }

    private static SchemaSnapshot.Table simple(String name, SchemaSnapshot.Column... extra) {
        List<SchemaSnapshot.Column> cols = new ArrayList<>();
        cols.add(col("id", "bigint", false));
        cols.addAll(List.of(extra));
        return table(name, cols, List.of("id"), List.of());
    }

    private static Map<String, String> render(List<SchemaSnapshot.Table> tables,
                                              List<SchemaSnapshot.ForeignKey> fks, Map<String, String> domains) {
        return ErDiagramRenderer.render(new SchemaSnapshot(tables, fks), domains);
    }

    private static String mermaidOf(String content) {
        int s = content.indexOf("```mermaid\n") + "```mermaid\n".length();
        return content.substring(s, content.indexOf("\n```", s));
    }

    // ─── AC-1 / AC-3 / AC-4 ───

    @Test
    @DisplayName("AC-1/3: README とドメインファイルが出る。テーブルもビューも0件のドメインはファイルを作らず、_unmapped は空なら作らない")
    void ドメインファイルの生成規則() {
        Map<String, String> files = render(List.of(simple("users"), simple("teams")), List.of(),
                Map.of("users", "user", "teams", "team", "ghost_domain_table", "ghost"));
        assertThat(files).containsOnlyKeys("README.md", "user.md", "team.md");
        assertThat(files.get("README.md")).contains("Entity 未紐付け（`_unmapped.md`）: テーブル 0 件 / ビュー 0 件");
    }

    @Test
    @DisplayName("AC-3/4: Entity 未紐付けのテーブル・ビューは _unmapped.md に入り、README に件数が出る。ビューは図に描かない")
    void 未紐付けとビュー() {
        Map<String, String> files = render(
                List.of(simple("users"), simple("orphan"), view("v_orphan"), view("v_users")), List.of(),
                Map.of("users", "user", "v_users", "user"));
        assertThat(files).containsKeys("_unmapped.md", "user.md");
        assertThat(files.get("_unmapped.md")).contains("### `orphan`").contains("## ビュー").contains("- `v_orphan`");
        assertThat(files.get("user.md")).contains("- `v_users`").contains("- ビュー数: 1");
        assertThat(files.get("README.md")).contains("テーブル 1 件 / ビュー 1 件");
        assertThat(mermaidOf(files.get("user.md"))).doesNotContain("v_users");
        assertThat(mermaidOf(files.get("_unmapped.md"))).doesNotContain("v_orphan");
    }

    @Test
    @DisplayName("AC-3: ビューだけのドメインでもファイルが出て、erDiagram ブロックは作らない")
    void ビューだけのドメイン() {
        Map<String, String> files = render(List.of(view("v1")), List.of(), Map.of("v1", "reporting"));
        assertThat(files).containsKey("reporting.md");
        assertThat(files.get("reporting.md")).doesNotContain("```mermaid");
    }

    // ─── AC-5 ───

    @Test
    @DisplayName("AC-5: 同一ドメイン内 FK は実線。子側列が NULL 可なら 0..1、NOT NULL なら 1")
    void FKの基数() {
        var users = simple("users");
        var posts = simple("posts", col("user_id", "bigint", false), col("editor_id", "bigint", true));
        List<SchemaSnapshot.ForeignKey> fks = List.of(
                new SchemaSnapshot.ForeignKey("posts", "fk_a", List.of("user_id"), "users", List.of("id")),
                new SchemaSnapshot.ForeignKey("posts", "fk_b", List.of("editor_id"), "users", List.of("id")));
        String m = mermaidOf(render(List.of(users, posts), fks, Map.of("users", "d", "posts", "d")).get("d.md"));
        assertThat(m).contains("users ||--o{ posts : \"user_id\"");
        assertThat(m).contains("users |o--o{ posts : \"editor_id\"");
        assertThat(m).doesNotContain("..");
    }

    @Test
    @DisplayName("AC-5: 参照先が他ドメインなら列なしのスタブ実体で描く")
    void 他ドメイン参照はスタブ() {
        var users = simple("users");
        var posts = simple("posts", col("user_id", "bigint", false));
        var fk = new SchemaSnapshot.ForeignKey("posts", "fk", List.of("user_id"), "users", List.of("id"));
        String m = mermaidOf(render(List.of(users, posts), List.of(fk), Map.of("users", "u", "posts", "p"))
                .get("p.md"));
        assertThat(m).contains("\n    users\n").contains("users ||--o{ posts");
        assertThat(m).doesNotContain("users {");
    }

    // ─── AC-6 / 7 / 8 / 9 ───

    @Test
    @DisplayName("AC-6: 参照推定は点線。型が参照先の主キーと一致しない列（*_by が VARCHAR 等）には線を引かない")
    void 参照推定と型不一致() {
        var users = simple("users");
        var teams = simple("teams");
        var shedlock = table("shedlock", List.of(col("name", "varchar", false), col("locked_by", "varchar", false)),
                List.of("name"), List.of());
        var badges = simple("user_badges", col("awarded_by", "varchar", true), col("user_id", "bigint", false),
                col("created_by", "bigint", true), col("team_id", "bigint", true));
        Map<String, String> files = render(List.of(users, teams, shedlock, badges), List.of(),
                Map.of("users", "d", "teams", "d", "shedlock", "d", "user_badges", "d"));
        String m = mermaidOf(files.get("d.md"));
        assertThat(m).contains("users ||..o{ user_badges : \"ID参照(FKなし) user_id\"");
        assertThat(m).contains("users |o..o{ user_badges : \"ID参照(FKなし) created_by\"");
        assertThat(m).contains("teams |o..o{ user_badges : \"ID参照(FKなし) team_id\"");
        assertThat(m).doesNotContain("awarded_by").doesNotContain("locked_by");
        assertThat(m.lines().filter(l -> l.contains("shedlock") && l.contains(":")).toList())
                .as("shedlock には関係線を引かない").isEmpty();
    }

    @Test
    @DisplayName("AC-7: 多態参照（<p>_type と <p>_id）は線を引かず表に列挙する（scope_type/scope_id 含む）")
    void 多態参照() {
        var users = simple("users");
        var teams = simple("teams");
        var notes = simple("notes", col("scope_type", "varchar", false), col("scope_id", "bigint", false),
                col("team_id", "bigint", true), col("target_type", "varchar", false),
                col("target_id", "bigint", false));
        String c = render(List.of(users, teams, notes), List.of(), Map.of("users", "d", "teams", "d", "notes", "d"))
                .get("d.md");
        assertThat(c).contains("## 多態参照（線を引かない）")
                .contains("| `notes` | `scope_type` | `scope_id` |")
                .contains("| `notes` | `target_type` | `target_id` |");
        assertThat(mermaidOf(c)).contains("teams |o..o{ notes").doesNotContain("scope_id")
                .doesNotContain("target_id");
        assertThat(c).doesNotContain("## 推定不能参照");
    }

    @Test
    @DisplayName("AC-8: 推定できない *_id は「推定不能参照」表に理由つきで列挙する（黙って落とさない）")
    void 推定不能参照() {
        var teams = table("teams", List.of(col("id", "char", false)), List.of("id"), List.of());
        var things = simple("things", col("mystery_id", "bigint", true), col("team_id", "bigint", true));
        String c = render(List.of(teams, things), List.of(), Map.of("teams", "d", "things", "d")).get("d.md");
        assertThat(c).contains("## 推定不能参照")
                .contains("| `things` | `mystery_id` | 参照先テーブルを特定できない |")
                .contains("| `things` | `team_id` | 型が参照先 teams.id と不一致（bigint ≠ char） |");
    }

    @Test
    @DisplayName("AC-9: 自己参照（parent_id）も描け、mermaid として成立する形になる")
    void 自己参照() {
        var cat = simple("categories", col("parent_id", "bigint", true));
        String m = mermaidOf(render(List.of(cat), List.of(), Map.of("categories", "d")).get("d.md"));
        assertThat(m).contains("categories |o..o{ categories : \"ID参照(FKなし) parent_id\"");
        var fkCat = simple("nodes", col("parent_id", "bigint", true));
        var fk = new SchemaSnapshot.ForeignKey("nodes", "fk", List.of("parent_id"), "nodes", List.of("id"));
        String m2 = mermaidOf(render(List.of(fkCat), List.of(fk), Map.of("nodes", "d")).get("d.md"));
        assertThat(m2).contains("nodes |o--o{ nodes : \"parent_id\"");
    }

    // ─── AC-10 / 11 / 12 ───

    @Test
    @DisplayName("AC-10: 入力順に依らず決定的。LF のみ・タイムスタンプなし・末尾改行は1つ")
    void 決定的な出力() {
        var users = simple("users");
        var posts = simple("posts", col("user_id", "bigint", false));
        var a = simple("a_tbl", col("user_id", "bigint", false));
        List<SchemaSnapshot.Table> t1 = List.of(users, posts, a);
        List<SchemaSnapshot.Table> t2 = new ArrayList<>(t1);
        Collections.reverse(t2);
        Map<String, String> d = Map.of("users", "x", "posts", "x", "a_tbl", "y");
        Map<String, String> f1 = render(t1, List.of(), d);
        Map<String, String> f2 = render(t2, List.of(), d);
        assertThat(f1).isEqualTo(f2);
        assertThat(f1).isEqualTo(render(t1, List.of(), d));
        f1.forEach((name, content) -> {
            assertThat(content).as(name).doesNotContain("\r").endsWith("\n").doesNotEndWith("\n\n");
            assertThat(content).as(name).doesNotContainPattern("20\\d\\d-\\d\\d-\\d\\d");
        });
    }

    @Test
    @DisplayName("AC-11: 実体名・属性型を mermaid の字句規則に正規化する")
    void 字句規則の正規化() {
        assertThat(ErDiagramRenderer.attrToken("varchar(255)", "t")).isEqualTo("varchar");
        assertThat(ErDiagramRenderer.attrToken("decimal(10,2)", "t")).isEqualTo("decimal");
        assertThat(ErDiagramRenderer.attrToken("2fa_enabled", "c")).isEqualTo("c_2fa_enabled");
        assertThat(ErDiagramRenderer.attrToken("a-b.c", "c")).isEqualTo("a_b_c");
        assertThat(ErDiagramRenderer.entityId("2fa_codes")).isEqualTo("t_2fa_codes");
        assertThat(ErDiagramRenderer.entityId("user-roles")).isEqualTo("user_roles");
        var weird = table("2fa_codes", List.of(col("id", "bigint", false), col("9x", "enum", false)), List.of("id"),
                List.of("9x"));
        String m = mermaidOf(render(List.of(weird), List.of(), Map.of("2fa_codes", "d")).get("d.md"));
        assertThat(m).contains("t_2fa_codes {").contains("enum c_9x UK").doesNotContain("(");
    }

    @Test
    @DisplayName("AC-12: mermaid ブロックが 50,000 文字ちょうどは合格、超過は失敗しドメイン名と文字数を示す")
    void mermaid上限() {
        String ok = "```mermaid\n" + "a".repeat(ErDiagramRenderer.MAX_MERMAID_CHARS) + "\n```\n";
        String ng = "```mermaid\n" + "a".repeat(ErDiagramRenderer.MAX_MERMAID_CHARS + 1) + "\n```\n";
        assertThat(ErDiagramRenderer.oversizedMermaidBlocks(Map.of("ok.md", ok))).isEmpty();
        assertThat(ErDiagramRenderer.oversizedMermaidBlocks(Map.of("ok.md", ok, "big.md", ng)))
                .singleElement().asString().contains("big").contains("50001");
    }

    // ─── AC-13 / 16 ───

    @Test
    @DisplayName("AC-13: 照合は内容差分・欠落・余剰を検出し、ファイル名と再生成コマンドをメッセージに含める")
    void 照合(@TempDir Path dir) throws IOException {
        Map<String, String> expected = new TreeMap<>(Map.of("README.md", "r\n", "a.md", "a\n", "b.md", "b\n"));
        ErDiagramFiles.replaceDirectory(dir.resolve("er"), expected);
        assertThat(ErDiagramFiles.diff(dir.resolve("er"), expected)).isEmpty();

        Files.writeString(dir.resolve("er/a.md"), "changed\n");
        Files.delete(dir.resolve("er/b.md"));
        Files.writeString(dir.resolve("er/extra.md"), "x\n");
        List<String> problems = ErDiagramFiles.diff(dir.resolve("er"), expected);
        assertThat(problems).hasSize(3);
        String msg = ErDiagramFiles.driftMessage(problems);
        assertThat(msg).contains("a.md").contains("b.md").contains("extra.md")
                .contains("cd backend && ./gradlew generateErDiagrams");
    }

    @Test
    @DisplayName("AC-16: 書き出し途中で失敗しても既存の er/ を半端に残さず、一時 dir も残さない")
    void 書き出しの原子性(@TempDir Path dir) throws IOException {
        Path er = dir.resolve("er");
        ErDiagramFiles.replaceDirectory(er, Map.of("README.md", "old\n"));
        // NUL を含むファイル名は書き出し中に InvalidPathException になる
        Map<String, String> broken = new TreeMap<>();
        broken.put("a.md", "new\n");
        broken.put("b\0.md", "new\n");
        assertThatThrownBy(() -> ErDiagramFiles.replaceDirectory(er, broken)).isInstanceOf(RuntimeException.class);
        assertThat(Files.readString(er.resolve("README.md"), StandardCharsets.UTF_8)).isEqualTo("old\n");
        assertThat(er.resolve("a.md")).doesNotExist();
        try (var s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactly("er");
        }
        // 成功時は完全に置き換わる（古いファイルは残らない）
        ErDiagramFiles.replaceDirectory(er, Map.of("x.md", "x\n"));
        try (var s = Files.list(er)) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactly("x.md");
        }
    }

    // ─── AC-20 ───

    @Test
    @DisplayName("AC-20: コメント中の | と改行が表を壊さない。NULL 既定と既定なしを区別する")
    void 列定義表のエスケープ() {
        var c1 = new SchemaSnapshot.Column("note", "varchar(10)", "varchar(10)", true, null, "", "a|b\nc <x> \\");
        var c2 = new SchemaSnapshot.Column("must", "int", "int", false, null, "", "");
        var c3 = new SchemaSnapshot.Column("lit", "varchar", "varchar(5)", true, "NULL", "", "");
        var c4 = new SchemaSnapshot.Column("e", "enum", "enum('a|b','c')", false, "a|b", "", "");
        var t = table("memo", List.of(col("id", "bigint", false), c1, c2, c3, c4), List.of("id"), List.of());
        String c = render(List.of(t), List.of(), Map.of("memo", "d")).get("d.md");
        List<String> rows = c.lines().filter(l -> l.startsWith("| `")).toList();
        assertThat(rows).hasSize(5);
        for (String row : rows) {
            assertThat(unescapedPipes(row)).as(row).isEqualTo(6);
        }
        assertThat(c).contains("| `note` | `varchar(10)` | YES | NULL | a\\|b<br>c &lt;x&gt; \\\\ |");
        assertThat(c).contains("| `must` | `int` | NO | （なし） |  |");
        assertThat(c).contains("| `lit` | `varchar(5)` | YES | `NULL` |  |");
        assertThat(c).contains("| `e` | `enum('a\\|b','c')` | NO | `a\\|b` |  |");
    }

    private static int unescapedPipes(String row) {
        int n = 0;
        for (int i = 0; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == '|') {
                n++;
            }
        }
        return n;
    }
}
