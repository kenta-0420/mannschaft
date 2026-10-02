package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: route 層の隔離が「十分」であることを、FE のページ実体を走査して検証する（Gate 基盤工事②）。
 *
 * <h2>なぜ必要か（{@link FeatureGateRouteMapGuardTest} だけでは足りない理由）</h2>
 * <p>台帳↔GATE_ROUTE_MAP の突き合わせは「gate_key に束縛が1本でもあるか」しか見ない。
 * つまり {@code FEATURE_SHIFT_ENABLED: ['/shift']} と1本書けば、{@code /my/shift}・
 * {@code /teams/[slug]/shifts} が丸ごと素通りでも永久に緑になる。
 * <b>「宣言があること」と「隔離が足りていること」は別問題</b>であり、後者を守るのが本番人である。</p>
 *
 * <h2>判定方法</h2>
 * <p>「そのページがどの機能に属するか」は静的には決まらないため、
 * <b>台帳の {@code release.route_keywords}（人間が宣言する所属キーワード）</b>を根拠に置く。
 * {@code frontend/app/pages/} 配下の .vue からルートパスを復元し、隔離対象機能の
 * keywords に一致するページが</p>
 * <ol>
 *   <li>GATE_ROUTE_MAP のいずれかのプレフィクスで覆われている、または</li>
 *   <li>台帳の {@code route_coverage_exclusions} に<b>理由付きで</b>宣言されている</li>
 * </ol>
 * <p>のどちらでもなければ違反とする。これにより<b>新規ページが黙って隔離漏れに加わることを防ぐ</b>。
 * 除外は理由必須（理由が空なら red）であり、無審査で膨らむ baseline にはならない。
 * <b>陳腐化（実在しないパス・既に覆われたパスの残留）も本番人が red にする</b>。</p>
 *
 * <h2>あわせて守るもの</h2>
 * <ul>
 *   <li><b>ガード対象プレフィクス配下に {@code auth: false} のページを置かせない。</b>
 *       middleware は未認証時に判定せず後段の auth へ委ねる（未ログインで 503 フルページに
 *       化けるのを避けるため）。委譲先の auth が効かない {@code auth: false} ページが
 *       ガード対象配下にあると、その委譲がそのまま素通りの穴になる。</li>
 * </ul>
 *
 * <h2>本テストは ArchUnit ではない</h2>
 * <p>YAML・TypeScript・.vue のソース走査であり、凍結ストアは一切使わない。</p>
 */
@DisplayName("番人: route ガードの隔離十分性（FE ページ実体の走査）")
class FeatureGatePageCoverageGuardTest {

    private static final Path INVENTORY = Paths.get("docs", "inventory", "feature-inventory.yaml");
    private static final Path GATES_TS = Paths.get("frontend", "app", "constants", "featureGates.ts");
    private static final Path PAGES_DIR = Paths.get("frontend", "app", "pages");

    private static final Set<String> ISOLATED_BETA = Set.of("β限定", "内部限定", "停止");

    @Test
    @DisplayName("隔離対象機能に属するページが、束縛済みか未カバー台帳に明示列挙されていること")
    void 隔離対象ページが覆われているか明示列挙されている() throws IOException {
        Path inventory = FeatureGateRouteMapGuardTest.resolveFromRepoRoot(INVENTORY);
        Path gatesTs = FeatureGateRouteMapGuardTest.resolveFromRepoRoot(GATES_TS);
        Path pagesDir = FeatureGateRouteMapGuardTest.resolveFromRepoRoot(PAGES_DIR);

        assertThat(Files.isRegularFile(inventory)).as("棚卸し台帳が見つからない: " + inventory).isTrue();
        assertThat(Files.isRegularFile(gatesTs)).as("route 束縛表が見つからない: " + gatesTs).isTrue();
        assertThat(Files.isDirectory(pagesDir)).as("FE ページディレクトリが見つからない: " + pagesDir).isTrue();

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(inventory)) {
            root = new Yaml().load(in);
        }

        // 隔離対象かつ gate_key を持つ行の route_keywords を集める。
        List<String> keywords = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Object> records = (List<Object>) root.get("records");
        for (Object o : records) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = (Map<String, Object>) o;
            Object relObj = rec.get("release");
            if (!(relObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> rel = (Map<String, Object>) relObj;
            if (rel.get("gate_key") == null) continue;
            String beta = String.valueOf(rel.get("beta"));
            if (!ISOLATED_BETA.contains(beta)) continue;
            Object kwObj = rel.get("route_keywords");
            if (kwObj instanceof List) {
                for (Object k : (List<?>) kwObj) {
                    keywords.add(String.valueOf(k).toLowerCase(Locale.ROOT));
                }
            }
        }
        assertThat(keywords)
                .as("隔離対象かつ gate_key ありの行に route_keywords が1件も無い。"
                        + "所属判定の根拠が消えると本番人は何も検出できなくなる")
                .isNotEmpty();

        // 除外ページ（理由必須）
        Set<String> excluded = new LinkedHashSet<>();
        List<String> reasonViolations = new ArrayList<>();
        Object exObj = root.get("route_coverage_exclusions");
        if (exObj instanceof List) {
            for (Object x : (List<?>) exObj) {
                if (!(x instanceof Map)) {
                    reasonViolations.add("route_coverage_exclusions の要素がマップでない: " + x);
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) x;
                String path = e.get("path") == null ? null : String.valueOf(e.get("path"));
                String reason = e.get("reason") == null ? "" : String.valueOf(e.get("reason")).trim();
                if (path == null || path.isBlank()) {
                    reasonViolations.add("route_coverage_exclusions に path の無いエントリがある");
                    continue;
                }
                if (reason.isEmpty()) {
                    reasonViolations.add("route ガードの除外に理由が無い: " + path
                            + " — 除外は必ず理由付きで宣言すること（無審査の素通りを作らないため）");
                }
                excluded.add(path);
            }
        }

        List<String> prefixes = FeatureGateRouteMapGuardTest.extractPrefixes(
                Files.readString(gatesTs, StandardCharsets.UTF_8));
        assertThat(prefixes).as("GATE_ROUTE_MAP からプレフィクスを抽出できなかった").isNotEmpty();

        List<String> violations = new ArrayList<>(reasonViolations);
        Set<String> seenExcluded = new LinkedHashSet<>();

        List<Path> vueFiles;
        try (Stream<Path> walk = Files.walk(pagesDir)) {
            vueFiles = walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".vue"))
                    .sorted()
                    .toList();
        }

        for (Path f : vueFiles) {
            String route = toRoutePath(pagesDir.relativize(f));
            boolean covered = isCovered(route, prefixes);

            if (covered) {
                // ガード対象配下に auth:false のページがあると、未認証委譲がそのまま穴になる。
                String body = Files.readString(f, StandardCharsets.UTF_8);
                if (body.replaceAll("\s", "").contains("auth:false")) {
                    violations.add("ガード対象プレフィクス配下に auth:false のページがある: " + route
                            + " — middleware は未認証時に判定せず auth へ委ねるため、"
                            + "auth が効かないページを対象配下に置くと素通りの穴になる。"
                            + "公開が必要なら GATE_ROUTE_MAP の束縛から外すこと");
                }
            }

            if (!matchesKeyword(route, keywords)) continue;

            if (covered) {
                if (excluded.contains(route)) {
                    violations.add("route_coverage_exclusions が陳腐化している（既に覆われている）: " + route
                            + " — 台帳の route_coverage_exclusions から削除すること");
                    seenExcluded.add(route);
                }
                continue;
            }

            if (excluded.contains(route)) {
                seenExcluded.add(route);
                continue;
            }

            violations.add("隔離対象機能に属するページが route 層で覆われていない: " + route
                    + " — GATE_ROUTE_MAP にプレフィクスを追加して隔離するか、"
                    + "隔離しない判断なら台帳の route_coverage_exclusions に理由付きで宣言すること"
                    + "（黙って素通りさせないための番人であり、除外は審査を伴う判断である）");
        }

        for (String p : excluded) {
            if (!seenExcluded.contains(p)) {
                violations.add("route_coverage_exclusions が陳腐化している（該当ページが存在しないか"
                        + " route_keywords に一致しない）: " + p + " — 台帳から削除すること");
            }
        }

        if (violations.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("route 層の隔離十分性に違反があります。\n")
                .append("本番人の検出を緩めて通すことは禁止。GATE_ROUTE_MAP か台帳を直すこと。\n")
                .append("違反一覧:\n");
        for (String v : violations) sb.append("  x ").append(v).append("\n");
        assertThat(violations).as(sb.toString()).isEmpty();
    }

    /** {@code app/pages} 相対の .vue パスから Nuxt のルートパスを復元する。 */
    static String toRoutePath(Path relative) {
        String p = relative.toString().replace('\\', '/');
        p = p.substring(0, p.length() - ".vue".length());
        if (p.endsWith("/index")) {
            p = p.substring(0, p.length() - "/index".length());
        } else if (p.equals("index")) {
            p = "";
        }
        return "/" + p;
    }

    /**
     * ルートパスがいずれかのプレフィクスで覆われるか判定する（セグメント単位）。
     *
     * <p>プレフィクス側の {@code *} だけが「任意の1セグメント」にマッチする。
     * ページ側の {@code [slug]} を無条件にワイルドカード扱いすると、
     * {@code /[id]} のような動的ページが任意のプレフィクスに覆われたことになり検出が抜けるため、
     * 一致は必ずプレフィクス側の {@code *} を根拠にする。FE の {@code prefixCovers} と同じ規則。
     * セグメント単位で比較するので {@code /shift} が {@code /shift-budget} を巻き込むことはない。</p>
     */
    static boolean isCovered(String route, List<String> prefixes) {
        String[] target = split(route);
        for (String prefix : prefixes) {
            String[] pre = split(prefix);
            if (target.length < pre.length) continue;
            boolean ok = true;
            for (int i = 0; i < pre.length; i++) {
                if (pre[i].equals("*")) continue;
                if (!pre[i].equals(target[i])) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }

    private static String[] split(String path) {
        return java.util.Arrays.stream(path.split("/"))
                .filter(s -> !s.isEmpty()).toArray(String[]::new);
    }


    static boolean matchesKeyword(String route, List<String> keywords) {
        String low = route.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (low.contains(k)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 自己検証: 本番人が実際に検出できることを合成入力で固定する
    // （検出器が何も検出できないまま緑、という偽陰性を防ぐ）。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自己検証: ルートパス復元が index / ネスト / 動的セグメントで正しいこと")
    void ルートパス復元() {
        assertThat(toRoutePath(Paths.get("shift", "index.vue"))).isEqualTo("/shift");
        assertThat(toRoutePath(Paths.get("my", "shift.vue"))).isEqualTo("/my/shift");
        assertThat(toRoutePath(Paths.get("teams", "[slug]", "shifts", "index.vue")))
                .isEqualTo("/teams/[slug]/shifts");
        assertThat(toRoutePath(Paths.get("index.vue"))).isEqualTo("/");
    }

    @Test
    @DisplayName("自己検証: 覆われていない所属ページを検出できること（プレフィクス1本では足りない）")
    void 未カバー検出() {
        List<String> prefixes = List.of("/shift");
        assertThat(isCovered("/shift/12", prefixes)).isTrue();
        // これが本番人の存在理由: /shift を1本書いても /my/shift は覆われない。
        assertThat(isCovered("/my/shift", prefixes)).isFalse();
        assertThat(matchesKeyword("/my/shift", List.of("shift"))).isTrue();
    }

    @Test
    @DisplayName("自己検証: ワイルドカードは1セグメントちょうどに対応し、動的ページを無条件に覆わない")
    void ワイルドカード() {
        assertThat(isCovered("/teams/[slug]/shifts", List.of("/teams/*/shifts"))).isTrue();
        assertThat(isCovered("/teams/[slug]/shifts/[id]/board", List.of("/teams/*/shifts"))).isTrue();
        assertThat(isCovered("/teams/[slug]/settings", List.of("/teams/*/shifts"))).isFalse();
        // 動的ページをワイルドカード扱いすると、これが true になって検出が抜ける。
        assertThat(isCovered("/[id]", List.of("/shift"))).isFalse();
    }

    @Test
    @DisplayName("自己検証: 隣接名を所属ページと誤検出しないこと")
    void 境界() {
        assertThat(isCovered("/shift-budget", List.of("/shift"))).isFalse();
        assertThat(matchesKeyword("/dashboard", List.of("shift", "market"))).isFalse();
    }
}
