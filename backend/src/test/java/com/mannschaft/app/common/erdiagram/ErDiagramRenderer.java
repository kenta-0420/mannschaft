package com.mannschaft.app.common.erdiagram;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link SchemaSnapshot} から docs/architecture/er/ 配下のファイル群（README.md・{@code <domain>.md}・
 * {@code _unmapped.md}）を決定的に組み立てる純ロジック。DB にも Spring にも依存しない。
 *
 * <p>決定性: 出力はすべて辞書順に固定し、タイムスタンプを含めず、改行は LF、末尾は改行 1 つ。
 */
final class ErDiagramRenderer {

    /** mermaid の既定 maxTextSize。これを超える図は描画されない。 */
    static final int MAX_MERMAID_CHARS = 50_000;
    static final String REGEN_COMMAND = "cd backend && ./gradlew generateErDiagrams";
    static final String UNMAPPED = "_unmapped";
    static final String README = "README.md";

    private static final Pattern MERMAID_BLOCK = Pattern.compile("```mermaid\n(.*?)\n```", Pattern.DOTALL);

    private ErDiagramRenderer() {
    }

    // ─── 公開 API ────────────────────────────────────────────────

    /**
     * @param tableDomains テーブル名（小文字）→ ドメイン。ここに無いテーブル/ビューは {@code _unmapped}。
     * @return ファイル名 → 内容（辞書順）
     */
    static Map<String, String> render(SchemaSnapshot snapshot, Map<String, String> tableDomains) {
        Model model = new Model(snapshot, tableDomains);
        Map<String, String> files = new TreeMap<>();
        for (Map.Entry<String, List<SchemaSnapshot.Table>> e : model.byDomain.entrySet()) {
            files.put(e.getKey() + ".md", renderDomain(model, e.getKey(), e.getValue()));
        }
        files.put(README, renderReadme(model));
        return files;
    }

    /** mermaid ブロックが {@link #MAX_MERMAID_CHARS} を超えるファイルを「ドメイン名: 文字数」で返す。 */
    static List<String> oversizedMermaidBlocks(Map<String, String> files) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(files).entrySet()) {
            Matcher m = MERMAID_BLOCK.matcher(e.getValue());
            while (m.find()) {
                int len = m.group(1).length();
                if (len > MAX_MERMAID_CHARS) {
                    String domain = e.getKey().replaceFirst("\\.md$", "");
                    violations.add(domain + ": " + len + "文字（上限 " + MAX_MERMAID_CHARS + "）");
                }
            }
        }
        return violations;
    }

    // ─── モデル（参照推定を含む） ────────────────────────────────

    /** 描く関係線。 */
    record Relation(String child, String parent, String label, boolean dotted, boolean parentOptional,
                    boolean childUnique, String columns) {
    }

    record Polymorphic(String table, String typeColumn, String idColumn) {
    }

    record Unresolved(String table, String column, String reason) {
    }

    /** 参照推定の結果を含む、描画前のスキーマ解釈。 */
    static final class Model {
        final Map<String, SchemaSnapshot.Table> tables = new TreeMap<>();
        final Map<String, List<SchemaSnapshot.Table>> byDomain = new TreeMap<>();
        final Map<String, String> domainOf = new TreeMap<>();
        final List<Relation> relations = new ArrayList<>();
        final List<Polymorphic> polymorphics = new ArrayList<>();
        final List<Unresolved> unresolved = new ArrayList<>();
        /** テーブル → 図に出すキー列の印（列名 → 印のリスト）。 */
        final Map<String, Map<String, Set<String>>> keyMarks = new TreeMap<>();
        /** 推定された参照列（テーブル名\0列名）。 */
        final Set<String> inferredColumns = new HashSet<>();

        Model(SchemaSnapshot snapshot, Map<String, String> tableDomains) {
            Map<String, String> domains = new TreeMap<>();
            tableDomains.forEach((k, v) -> domains.put(k.toLowerCase(Locale.ROOT), v));
            for (SchemaSnapshot.Table t : snapshot.tables()) {
                tables.put(t.name(), t);
                String d = domains.getOrDefault(t.name().toLowerCase(Locale.ROOT), UNMAPPED);
                domainOf.put(t.name(), d);
                byDomain.computeIfAbsent(d, k -> new ArrayList<>()).add(t);
            }
            byDomain.values().forEach(l -> l.sort(Comparator.comparing(SchemaSnapshot.Table::name)));

            // PK / UK の印
            for (SchemaSnapshot.Table t : tables.values()) {
                if (t.view()) {
                    continue;
                }
                t.primaryKey().forEach(c -> mark(t.name(), c, "PK"));
                t.uniqueColumns().forEach(c -> mark(t.name(), c, "UK"));
            }

            // 実 FK（同一ドメイン内でも他ドメインでも実線。参照先が他ドメインならスタブ実体で描く）
            Set<String> fkColumns = new HashSet<>();
            for (SchemaSnapshot.ForeignKey fk : snapshot.foreignKeys()) {
                SchemaSnapshot.Table child = tables.get(fk.table());
                if (child == null || child.view() || !tables.containsKey(fk.refTable())) {
                    continue;
                }
                boolean nullable = fk.columns().stream().anyMatch(c -> column(child, c).nullable());
                boolean unique = fk.columns().size() == 1 && (child.uniqueColumns().contains(fk.columns().get(0))
                        || child.primaryKey().equals(fk.columns()));
                fk.columns().forEach(c -> {
                    mark(fk.table(), c, "FK");
                    fkColumns.add(fk.table() + "\0" + c);
                });
                relations.add(new Relation(fk.table(), fk.refTable(), String.join(", ", fk.columns()), false,
                        nullable, unique, String.join(", ", fk.columns())));
            }

            // 多態参照と参照推定
            for (SchemaSnapshot.Table t : tables.values()) {
                if (t.view()) {
                    continue;
                }
                Set<String> names = new HashSet<>();
                t.columns().forEach(c -> names.add(c.name()));
                Set<String> polyIdColumns = new HashSet<>();
                for (SchemaSnapshot.Column c : t.columns()) {
                    if (c.name().endsWith("_id") && !fkColumns.contains(t.name() + "\0" + c.name())) {
                        String prefix = c.name().substring(0, c.name().length() - 3);
                        if (!prefix.isEmpty() && names.contains(prefix + "_type")) {
                            polymorphics.add(new Polymorphic(t.name(), prefix + "_type", c.name()));
                            polyIdColumns.add(c.name());
                        }
                    }
                }
                for (SchemaSnapshot.Column c : t.columns()) {
                    String key = t.name() + "\0" + c.name();
                    if (fkColumns.contains(key) || polyIdColumns.contains(c.name())) {
                        continue;
                    }
                    infer(t, c);
                }
            }
            relations.sort(Comparator.comparing(Relation::child).thenComparing(Relation::parent)
                    .thenComparing(Relation::label).thenComparing(Relation::dotted));
            polymorphics.sort(Comparator.comparing(Polymorphic::table).thenComparing(Polymorphic::idColumn));
            unresolved.sort(Comparator.comparing(Unresolved::table).thenComparing(Unresolved::column));
            // 同一関係の重複排除
            Set<String> seen = new HashSet<>();
            relations.removeIf(r -> !seen.add(r.child() + "\0" + r.parent() + "\0" + r.label() + "\0" + r.dotted()));
        }

        private void mark(String table, String column, String mark) {
            keyMarks.computeIfAbsent(table, k -> new TreeMap<>())
                    .computeIfAbsent(column, k -> new TreeSet<>(Comparator.comparingInt(Model::markOrder)))
                    .add(mark);
        }

        private static int markOrder(String m) {
            return switch (m) {
                case "PK" -> 0;
                case "FK" -> 1;
                default -> 2;
            };
        }

        private static SchemaSnapshot.Column column(SchemaSnapshot.Table t, String name) {
            return t.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
        }

        /** 1 列の参照を推定し、線を引く／推定不能に列挙する／何もしない、のいずれかにする。 */
        private void infer(SchemaSnapshot.Table t, SchemaSnapshot.Column c) {
            String name = c.name();
            boolean isId = name.endsWith("_id");
            boolean isBy = name.endsWith("_by");
            if (!isId && !isBy) {
                return;
            }
            String target = null;
            if (isBy || name.equals("user_id") || name.endsWith("_user_id")) {
                target = tables.containsKey("users") && !tables.get("users").view() ? "users" : null;
            } else if (name.equals("parent_id")) {
                target = t.name();
            } else {
                String[] words = name.substring(0, name.length() - 3).split("_");
                outer:
                for (int i = 0; i < words.length; i++) {
                    String stem = String.join("_", java.util.Arrays.copyOfRange(words, i, words.length));
                    for (String plural : plurals(stem)) {
                        SchemaSnapshot.Table cand = tables.get(plural);
                        if (cand != null && !cand.view()) {
                            target = plural;
                            break outer;
                        }
                    }
                }
            }
            if (target == null) {
                if (isId) {
                    unresolved.add(new Unresolved(t.name(), name, "参照先テーブルを特定できない"));
                }
                return;
            }
            SchemaSnapshot.Table tt = tables.get(target);
            if (tt.primaryKey().size() != 1) {
                if (isId) {
                    unresolved.add(new Unresolved(t.name(), name, "参照先 " + target + " が単一列の主キーでない"));
                }
                return;
            }
            SchemaSnapshot.Column pk = column(tt, tt.primaryKey().get(0));
            if (!pk.dataType().equals(c.dataType())) {
                // 型が違えば参照ではない（例: shedlock.locked_by VARCHAR、user_badges.awarded_by に 'SYSTEM'）
                if (isId) {
                    unresolved.add(new Unresolved(t.name(), name,
                            "型が参照先 " + target + "." + pk.name() + " と不一致（" + c.dataType() + " ≠ "
                                    + pk.dataType() + "）"));
                }
                return;
            }
            // 実 FK ではないので FK マーカーは付けない（図には列だけ出し、"ID参照" で示す）
            inferredColumns.add(t.name() + "\0" + name);
            boolean unique = t.uniqueColumns().contains(name) || t.primaryKey().equals(List.of(name));
            relations.add(new Relation(t.name(), target, "ID参照(FKなし) " + name, true, c.nullable(), unique, name));
        }
    }

    /** 語幹の複数形候補（実在判定は呼び出し側）。 */
    static List<String> plurals(String stem) {
        List<String> out = new ArrayList<>();
        out.add(stem + "s");
        if (stem.endsWith("y") && stem.length() > 1 && "aeiou".indexOf(stem.charAt(stem.length() - 2)) < 0) {
            out.add(stem.substring(0, stem.length() - 1) + "ies");
        }
        if (stem.endsWith("s") || stem.endsWith("x") || stem.endsWith("z") || stem.endsWith("ch")
                || stem.endsWith("sh")) {
            out.add(stem + "es");
        }
        return out;
    }

    // ─── ドメインファイル ─────────────────────────────────────────

    private static String renderDomain(Model m, String domain, List<SchemaSnapshot.Table> members) {
        List<SchemaSnapshot.Table> baseTables = members.stream().filter(t -> !t.view()).toList();
        List<SchemaSnapshot.Table> views = members.stream().filter(SchemaSnapshot.Table::view).toList();
        Set<String> memberNames = new TreeSet<>();
        baseTables.forEach(t -> memberNames.add(t.name()));

        Lines out = new Lines();
        out.add("# " + domain + " ドメイン ER 図");
        out.add("");
        out.add("> このファイルは自動生成されます。手で編集しないでください。再生成: `" + REGEN_COMMAND + "`");
        out.add("");
        out.add("- テーブル数: " + baseTables.size());
        out.add("- ビュー数: " + views.size());
        out.add("");
        if (!baseTables.isEmpty()) {
            out.add("## ER 図");
            out.add("");
            out.add("実線 = 外部キー、点線 = ID参照(FKなし)。図中の `FK` は実外部キーの列だけ（ID参照列は `FK` を付けず \"ID参照\" と注記）。"
                    + "図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE）のみを描く。"
                    + "全列は下の「テーブル定義」を参照。");
            out.add("");
            out.add("```mermaid");
            out.add(mermaid(m, memberNames));
            out.add("```");
            out.add("");
        }

        renderInbound(m, domain, memberNames, out);

        // mermaid は同一テーブルの自己参照を複数描けず 1 本に畳むため、全件を表で正として示す
        List<Relation> selfRefs = m.relations.stream()
                .filter(r -> r.child().equals(r.parent()) && memberNames.contains(r.child()))
                .sorted(Comparator.comparing(Relation::child).thenComparing(Relation::columns))
                .toList();
        if (!selfRefs.isEmpty()) {
            out.add("## 自己参照");
            out.add("");
            out.add("mermaid は同一テーブルの自己参照を複数描けず 1 本に畳むため、図に線が欠けることがある。"
                    + "自己参照はこの表を正とする。");
            out.add("");
            out.add("| テーブル | 列 | 種別 |");
            out.add("| --- | --- | --- |");
            selfRefs.forEach(r -> out.add("| `" + r.child() + "` | `" + r.columns() + "` | "
                    + (r.dotted() ? "ID参照" : "外部キー") + " |"));
            out.add("");
        }

        List<Polymorphic> poly = m.polymorphics.stream().filter(p -> memberNames.contains(p.table())).toList();
        if (!poly.isEmpty()) {
            out.add("## 多態参照（線を引かない）");
            out.add("");
            out.add("| テーブル | 種別列 | ID列 |");
            out.add("| --- | --- | --- |");
            poly.forEach(p -> out.add("| `" + p.table() + "` | `" + p.typeColumn() + "` | `" + p.idColumn() + "` |"));
            out.add("");
        }
        List<Unresolved> unres = m.unresolved.stream().filter(u -> memberNames.contains(u.table())).toList();
        if (!unres.isEmpty()) {
            out.add("## 推定不能参照");
            out.add("");
            out.add("| テーブル | 列 | 理由 |");
            out.add("| --- | --- | --- |");
            unres.forEach(u -> out.add("| `" + u.table() + "` | `" + u.column() + "` | " + plainCell(u.reason())
                    + " |"));
            out.add("");
        }

        if (!baseTables.isEmpty()) {
            out.add("## テーブル定義");
            out.add("");
            for (SchemaSnapshot.Table t : baseTables) {
                out.add("### `" + t.name() + "`");
                out.add("");
                out.add("| 列名 | 型 | NULL | 既定値 | コメント |");
                out.add("| --- | --- | --- | --- | --- |");
                for (SchemaSnapshot.Column c : t.columns()) {
                    out.add("| `" + c.name() + "` | " + codeCell(c.columnType()) + " | " + (c.nullable() ? "YES" : "NO")
                            + " | " + defaultCell(c) + " | " + plainCell(c.comment() == null ? "" : c.comment())
                            + " |");
                }
                out.add("");
            }
        }
        if (!views.isEmpty()) {
            out.add("## ビュー");
            out.add("");
            for (SchemaSnapshot.Table v : views) {
                StringBuilder cols = new StringBuilder();
                for (SchemaSnapshot.Column c : v.columns()) {
                    if (cols.length() > 0) {
                        cols.append(", ");
                    }
                    cols.append(c.name());
                }
                out.add("- `" + v.name() + "` （列: " + plainCell(cols.toString()) + "）");
            }
            out.add("");
        }
        return out.finish();
    }

    /** 被参照（他ドメインの表がこのドメインの表を参照している側）の逆引き索引。 */
    private static void renderInbound(Model m, String domain, Set<String> memberNames, Lines out) {
        List<Relation> inbound = m.relations.stream()
                .filter(r -> memberNames.contains(r.parent()) && !m.domainOf.get(r.child()).equals(domain))
                .sorted(Comparator.comparing((Relation r) -> m.domainOf.get(r.child()))
                        .thenComparing(Relation::child).thenComparing(Relation::columns)
                        .thenComparing(Relation::parent).thenComparing(Relation::dotted))
                .toList();
        out.add("## 被参照（他ドメインから）");
        out.add("");
        out.add("このドメインのテーブルを参照している他ドメインの側（実 FK と ID参照のみ。"
                + "多態参照と推定不能参照は含まない。同一ドメイン内の参照は上の図を参照）。");
        out.add("");
        if (inbound.isEmpty()) {
            out.add("なし");
        } else {
            out.add("| 参照元ドメイン | 参照元テーブル | 列 | 種別 | 参照先テーブル |");
            out.add("| --- | --- | --- | --- | --- |");
            for (Relation r : inbound) {
                String d = m.domainOf.get(r.child());
                out.add("| [" + d + "](" + d + ".md) | `" + r.child() + "` | `" + r.columns() + "` | "
                        + (r.dotted() ? "ID参照" : "FK") + " | `" + r.parent() + "` |");
            }
        }
        out.add("");
    }

    // ─── mermaid ──────────────────────────────────────────────────

    private static String mermaid(Model m, Set<String> memberNames) {
        Lines out = new Lines();
        out.add("erDiagram");
        Map<String, String> ids = new TreeMap<>();
        Set<String> usedIds = new HashSet<>();
        List<Relation> rels = m.relations.stream().filter(r -> memberNames.contains(r.child())).toList();
        Set<String> stubs = new TreeSet<>();
        for (Relation r : rels) {
            if (!memberNames.contains(r.parent())) {
                stubs.add(r.parent());
            }
        }
        List<String> all = new ArrayList<>(memberNames);
        all.addAll(stubs);
        for (String t : all) {
            String id = entityId(t);
            if (!usedIds.add(id)) {
                throw new IllegalStateException("mermaid 実体名が衝突した: " + t + " → " + id);
            }
            ids.put(t, id);
        }
        for (String name : memberNames) {
            SchemaSnapshot.Table t = m.tables.get(name);
            out.add("    " + ids.get(name) + " {");
            Map<String, Set<String>> marks = m.keyMarks.getOrDefault(name, Map.of());
            for (SchemaSnapshot.Column c : t.columns()) {
                Set<String> mk = marks.get(c.name());
                boolean inferred = m.inferredColumns.contains(name + "\0" + c.name());
                if ((mk == null || mk.isEmpty()) && !inferred) {
                    continue;
                }
                StringBuilder line = new StringBuilder("        ");
                line.append(attrToken(c.dataType(), "t")).append(' ').append(attrToken(c.name(), "c"));
                if (mk != null && !mk.isEmpty()) {
                    line.append(' ').append(String.join(", ", mk));
                }
                if (inferred) {
                    line.append(" \"ID参照\"");
                }
                out.add(line.toString());
            }
            out.add("    }");
        }
        for (String stub : stubs) {
            out.add("    " + ids.get(stub));
        }
        for (Relation r : rels) {
            out.add("    " + ids.get(r.parent()) + " " + (r.parentOptional() ? "|o" : "||")
                    + (r.dotted() ? ".." : "--") + (r.childUnique() ? "o|" : "o{") + " " + ids.get(r.child())
                    + " : \"" + r.label().replace("\"", "'") + "\"");
        }
        return out.joinNoFinish();
    }

    /** mermaid 実体名の字句規則（英字/下線始まりの英数字・下線）へ正規化する。 */
    static String entityId(String table) {
        String s = table.replaceAll("[^A-Za-z0-9_]", "_");
        if (s.isEmpty() || Character.isDigit(s.charAt(0)) || MERMAID_RESERVED.contains(s.toLowerCase(Locale.ROOT))) {
            s = "t_" + s;
        }
        return s;
    }

    /**
     * mermaid@11.4.1 erDiagram の字句規則（大文字小文字無視・単語境界）で、実体名として構文エラーになる語。
     * 出典: mermaid の erDiagram 字句規則（erDiagram / one / many / to / u）。mermaid.parse で実測確認済み。
     */
    private static final Set<String> MERMAID_RESERVED = Set.of("erdiagram", "one", "many", "to", "u");

    /** 属性の型・名前を mermaid の字句規則へ正規化する（varchar(255) → varchar、先頭数字は接頭辞付与）。 */
    static String attrToken(String raw, String digitPrefix) {
        String base = raw;
        int paren = base.indexOf('(');
        if (paren > 0) {
            base = base.substring(0, paren);
        }
        String s = base.trim().replaceAll("[^A-Za-z0-9_]", "_");
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_')) {
            s = digitPrefix + "_" + s;
        }
        return s;
    }

    // ─── README ───────────────────────────────────────────────────

    private static String renderReadme(Model m) {
        int totalTables = 0;
        int totalViews = 0;
        Lines rows = new Lines();
        int domainCount = 0;
        for (Map.Entry<String, List<SchemaSnapshot.Table>> e : m.byDomain.entrySet()) {
            if (e.getKey().equals(UNMAPPED)) {
                continue;
            }
            domainCount++;
            long tb = e.getValue().stream().filter(t -> !t.view()).count();
            long vw = e.getValue().size() - tb;
            totalTables += tb;
            totalViews += vw;
            rows.add("| [" + e.getKey() + "](" + e.getKey() + ".md) | " + tb + " | " + vw + " |");
        }
        long unTables = 0;
        long unViews = 0;
        List<SchemaSnapshot.Table> un = m.byDomain.get(UNMAPPED);
        if (un != null) {
            unTables = un.stream().filter(t -> !t.view()).count();
            unViews = un.size() - unTables;
            rows.add("| [" + UNMAPPED + "](" + UNMAPPED + ".md) | " + unTables + " | " + unViews + " |");
        }

        Lines out = new Lines();
        out.add("# ER 図（実スキーマからの自動生成）");
        out.add("");
        out.add("> このディレクトリは自動生成されます。手で編集しないでください。");
        out.add("");
        out.add("Flyway を全適用した MySQL の `information_schema` から、ドメイン単位の mermaid `erDiagram` と"
                + "全列定義表を生成したものです。");
        out.add("");
        out.add("**`docs/features` 等の手描き図は設計意図、本ディレクトリの図は現実のスキーマ**です。"
                + "両者が食い違う場合は本ディレクトリが現状を表します。");
        out.add("");
        out.add("## 再生成と照合");
        out.add("");
        out.add("```");
        out.add(REGEN_COMMAND);
        out.add("cd backend && ./gradlew checkErDiagrams");
        out.add("```");
        out.add("");
        out.add("Docker が必要です。CI（`er-diagram-drift-check`）は migration・Entity・生成器の変更時に照合し、"
                + "再生成漏れを検出します。");
        out.add("");
        out.add("## 凡例");
        out.add("");
        out.add("- 図中の `FK` マーカーは information_schema 上の**実外部キー列だけ**に付く。"
                + "ID参照（推定）の列には付かず、`\"ID参照\"` と注記する。");
        out.add("- 列定義表の「コメント」列は migration の COMMENT をそのまま転記したもの。"
                + "「FK → teams」とあっても実 FK とは限らない。");
        out.add("- あるテーブルを参照している側を探すには、そのテーブルが属するドメインのページの「被参照」節を見る。"
                + "多態参照・推定不能参照は被参照に出ないので、網羅確認では各ファイルの表を別途見ること。"
                + "`" + UNMAPPED + "`（ドメイン未割当）のテーブルは通常のドメインと同じく、参照元としても参照先としても被参照に載る。");
        out.add("- 実線: 外部キー（FK）。子側の列が NULL 可なら親側 `0..1`、NOT NULL なら `1`。");
        out.add("- 点線: ID参照(FKなし)。`user_id` / `*_user_id` / `*_by` は `users`、`<x>_id` は `x` の複数形テーブルへ、"
                + "**列の DATA_TYPE が参照先の主キーと一致する場合だけ**推定して引く。");
        out.add("- 多態参照（同一テーブルに `<p>_type` と `<p>_id` がある）は線を引かず、各ファイルの表に列挙する。");
        out.add("- 推定できない `*_id` は各ファイルの「推定不能参照」表に列挙する。");
        out.add("- mermaid は同一テーブルの自己参照を複数描けず1本に畳むため、自己参照は各ページの「自己参照」表を正とする。");
        out.add("- 参照先が他ドメインの場合は、列を持たないスタブ実体として描く。");
        out.add("- 図にはキー列（PK / FK / 参照推定列 / 単一列 UNIQUE=UK）のみを描く。全列は各ファイルの表。");
        out.add("- ビューは図に描かず、各ファイルの「ビュー」節に列挙する。");
        out.add("");
        out.add("## ドメイン一覧");
        out.add("");
        out.add("| ドメイン | テーブル数 | ビュー数 |");
        out.add("| --- | --- | --- |");
        out.addAll(rows);
        out.add("");
        out.add("- ドメイン数（`" + UNMAPPED + "` を除く）: " + domainCount);
        out.add("- テーブル合計: " + (totalTables + unTables) + "（`" + InformationSchemaReader.FLYWAY_HISTORY
                + "` を除く）");
        out.add("- ビュー合計: " + (totalViews + unViews));
        out.add("- Entity 未紐付け（`" + UNMAPPED + ".md`）: テーブル " + unTables + " 件 / ビュー " + unViews + " 件");
        out.add("");
        return out.finish();
    }

    // ─── セル整形 ─────────────────────────────────────────────────

    /** Markdown 表のセルに入れる素のテキストを、表を壊さないようエスケープする。 */
    static String plainCell(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>");
    }

    /** コードスパンとして入れる値。バッククォートを含む値は区切りを伸ばす。 */
    static String codeCell(String s) {
        String v = s.replace("\r\n", " ").replace("\n", " ").replace("\r", " ").replace("|", "\\|");
        int maxRun = 0;
        int run = 0;
        for (char c : v.toCharArray()) {
            run = c == '`' ? run + 1 : 0;
            maxRun = Math.max(maxRun, run);
        }
        String fence = "`".repeat(maxRun + 1);
        return maxRun > 0 ? fence + " " + v + " " + fence : fence + v + fence;
    }

    /** 既定値セル。NULL 既定と既定なしを区別する。 */
    static String defaultCell(SchemaSnapshot.Column c) {
        String extra = c.extra() == null ? "" : c.extra().toLowerCase(Locale.ROOT);
        String base;
        if (c.defaultValue() == null) {
            base = c.nullable() ? "NULL" : "（なし）";
        } else if (c.defaultValue().isEmpty()) {
            base = "（空文字）";
        } else {
            base = codeCell(c.defaultValue());
            if (extra.contains("default_generated")) {
                base += " （式）";
            }
        }
        if (extra.contains("auto_increment")) {
            base += " auto_increment";
        }
        return base;
    }

    /** 行バッファ。出力は LF・末尾改行 1 つに固定する。 */
    private static final class Lines {
        private final List<String> lines = new ArrayList<>();

        void add(String s) {
            lines.add(s);
        }

        void addAll(Lines other) {
            lines.addAll(other.lines);
        }

        String joinNoFinish() {
            return String.join("\n", lines);
        }

        String finish() {
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return String.join("\n", lines) + "\n";
        }
    }

    /** デバッグ・テスト用: ドメイン別ファイルのうちテーブル本体を持つものの名前。 */
    static Map<String, Integer> definitionCounts(Map<String, String> files) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Pattern p = Pattern.compile("^### `([^`]+)`$", Pattern.MULTILINE);
        files.values().forEach(c -> {
            Matcher m = p.matcher(c);
            while (m.find()) {
                counts.merge(m.group(1), 1, Integer::sum);
            }
        });
        return counts;
    }
}
