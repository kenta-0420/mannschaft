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
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: 棚卸し台帳 {@code docs/inventory/feature-inventory.yaml} の {@code release.gate_key} と
 * FE の route 束縛表 {@code frontend/app/constants/featureGates.ts} の
 * {@code GATE_ROUTE_MAP} が一致していること（Gate 基盤工事②）。
 *
 * <h2>なぜ「生成」ではなく「番人」か</h2>
 * <p>gate_key の唯一の発行元は棚卸し台帳（YAML）である。しかし FE に YAML パーサ依存を
 * 追加したりビルド時コード生成を挟むと、FE のビルド経路が台帳の書式に結合してしまう。
 * そこで FE 側は素の TypeScript 定数として route 束縛を持ち、
 * <b>二重管理の破綻はこの番人テストが CI で検出する</b>という分担にした。</p>
 *
 * <h2>本テストが守るもの</h2>
 * <ul>
 *   <li>(i) コード側（GATE_ROUTE_MAP）にあり YAML に無い gate_key を検出する
 *       — 綴り間違いは「全ユーザーに機能封鎖」として現れ、原因が見えない
 *       （{@code isEnabled} の {@code ?? false} が未登録キーにも false を返すため）。</li>
 *   <li>(ii) YAML で隔離対象（{@code release.beta} が β限定 / 内部限定 / 停止）かつ
 *       {@code gate_key != null} なのにコード側に route 束縛が無いものを検出する
 *       — route 層の隔離漏れ（未公開機能に URL 直打ちで到達できる穴）。</li>
 *   <li>(iii) コード側の gate_key 重複を検出する。</li>
 *   <li>(iv)〜(vii) {@code route_binding_exclusions}（下記）の理由欠落・陳腐化・綴り間違い、および
 *       <b>BE ゲート（{@code @RequireFeature}）を持たない除外</b>を検出する。</li>
 * </ul>
 *
 * <h2>本テストは ArchUnit ではない</h2>
 * <p>YAML と TypeScript ソースのテキスト走査であり、バイトコード解析ではない。
 * {@link FeatureInventorySchemaGuardTest} と同じ<b>ソース走査型</b>で書いた。
 * ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）は一切使わない。</p>
 *
 * <h2>検出を緩めて通すことは禁止</h2>
 * <p>違反が出た場合は台帳側か FE の束縛表側を直すこと。隔離対象で route 束縛を持てない
 * （専用の top-level route が無い等）機能は、YAML の {@code gate_key} を {@code null} の
 * ままにしておく（route 層の対象外であることを台帳上で明示する）。</p>
 *
 * <h2>BE 専用ゲートの明示除外（{@code route_binding_exclusions}）</h2>
 * <p><b>gate_key を null にできない例外が1種類だけある。</b> BE の {@code @RequireFeature} に
 * 使うキーは台帳と seed の積集合に実在しなければならない（{@link FeatureGateAnnotationKeyGuardTest}）。
 * つまり「専用 URL は1本も無いが BE 入口だけ塞ぐ」機能は、gate_key を発行せざるを得ず、
 * かつ束縛すべき route を持たない。実例: {@code FEATURE_SHIFT_AUTO_ASSIGN_ENABLED}
 * （自動割当の UI は {@code /teams/&#42;/shifts/&#42;/board} のタブ・ボタンであり、束縛すると
 * 親機能 shift の画面全体を巻き添えで遮断してしまう）。</p>
 * <p>この場合に限り台帳トップレベルの {@code route_binding_exclusions} に
 * <b>理由付きで</b>宣言する。適用条件（BE 入口を実際に塞いでいること）は (vii) が
 * {@code @RequireFeature} との照合で機械的に検証するため、「理由さえ書けば何でも除外できる」
 * 抜け道にはならない（Codex 検分 P2）。これは検出の緩和ではなく、
 * {@code route_coverage_exclusions}（{@link FeatureGatePageCoverageGuardTest}）と同じ
 * <b>理由必須・陳腐化検出つきの明示宣言</b>である: 理由が空なら (iv)、束縛済みなのに残っていれば
 * (v)、台帳に対応が無ければ (vi) で red になる。</p>
 */
@DisplayName("番人: 棚卸し台帳の gate_key と FE route 束縛表(GATE_ROUTE_MAP)の一致")
class FeatureGateRouteMapGuardTest {

    private static final Path FEATURE_INVENTORY_RELATIVE =
            Paths.get("docs", "inventory", "feature-inventory.yaml");

    private static final Path FEATURE_GATES_TS_RELATIVE =
            Paths.get("frontend", "app", "constants", "featureGates.ts");

    /** 隔離対象のβ区分（この区分の機能は route 層でも塞ぐ必要がある）。 */
    private static final Set<String> ISOLATED_BETA_VALUES = Set.of("β限定", "内部限定", "停止");

    /** {@code export const GATE_ROUTE_MAP ... = { ... }} のオブジェクトリテラル本体を切り出す。 */
    private static final Pattern GATE_ROUTE_MAP_BLOCK = Pattern.compile(
            "export\\s+const\\s+GATE_ROUTE_MAP\\s*(?::[^=]*)?=\\s*\\{(.*?)\\n\\}",
            Pattern.DOTALL);

    /** ブロック内の "  KEY: [" 行から gate_key を拾う。 */
    private static final Pattern GATE_KEY_ENTRY = Pattern.compile(
            "(?m)^\\s{2}([A-Z][A-Z0-9_]*)\\s*:\\s*\\[");

    @Test
    @DisplayName("台帳の gate_key と GATE_ROUTE_MAP が双方向に整合すること")
    void gateKeyとrouteマップが整合する() throws IOException {
        Path yamlPath = resolveFromRepoRoot(FEATURE_INVENTORY_RELATIVE);
        Path tsPath = resolveFromRepoRoot(FEATURE_GATES_TS_RELATIVE);

        assertThat(Files.isRegularFile(yamlPath))
                .as("棚卸し台帳が見つからない: " + yamlPath.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）")
                .isTrue();
        assertThat(Files.isRegularFile(tsPath))
                .as("FE の route 束縛表が見つからない: " + tsPath.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）。"
                        + "Gate 基盤工事②で新設した frontend/app/constants/featureGates.ts が必要")
                .isTrue();

        // ---- 台帳側 ----
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(yamlPath)) {
            root = new Yaml().load(in);
        }
        assertThat(root).as("棚卸し台帳のパースに失敗した").isNotNull();

        Object recordsObj = root.get("records");
        assertThat(recordsObj).as("棚卸し台帳に records キーが存在しない").isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> records = (List<Object>) recordsObj;

        Set<String> yamlGateKeys = new LinkedHashSet<>();
        Map<String, String> isolatedGateKeys = new LinkedHashMap<>();

        for (Object recordObj : records) {
            if (!(recordObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> record = (Map<String, Object>) recordObj;
            String featureKey = String.valueOf(record.get("feature_key"));

            Object releaseObj = record.get("release");
            if (!(releaseObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> release = (Map<String, Object>) releaseObj;

            Object gateKeyObj = release.get("gate_key");
            if (gateKeyObj == null) {
                continue;
            }
            String gateKey = String.valueOf(gateKeyObj);
            yamlGateKeys.add(gateKey);

            String beta = release.get("beta") == null ? null : String.valueOf(release.get("beta"));
            if (beta != null && ISOLATED_BETA_VALUES.contains(beta)) {
                isolatedGateKeys.put(gateKey, featureKey);
            }
        }

        // ---- コード側 ----
        String ts = Files.readString(tsPath, StandardCharsets.UTF_8);
        Matcher blockMatcher = GATE_ROUTE_MAP_BLOCK.matcher(ts);
        assertThat(blockMatcher.find())
                .as("GATE_ROUTE_MAP のオブジェクトリテラルを "
                        + FEATURE_GATES_TS_RELATIVE + " から切り出せなかった。"
                        + "宣言の書式を変えた場合は本番人の GATE_ROUTE_MAP_BLOCK 正規表現も追随させること")
                .isTrue();
        String block = blockMatcher.group(1);

        List<String> codeGateKeys = new ArrayList<>();
        Matcher entryMatcher = GATE_KEY_ENTRY.matcher(block);
        while (entryMatcher.find()) {
            codeGateKeys.add(entryMatcher.group(1));
        }

        assertThat(codeGateKeys)
                .as("GATE_ROUTE_MAP から gate_key を1件も抽出できなかった（正規表現と書式の乖離を疑う）")
                .isNotEmpty();

        Map<String, String> bindingExclusions = parseRouteBindingExclusions(root);

        // (vii) の照合元。抽出は FeatureGateAnnotationKeyGuardTest と同一手段を流用する（二重化しない）。
        Set<String> beGatedKeys = FeatureGateAnnotationKeyGuardTest.annotatedFeatureKeys();

        List<String> violations = detectViolations(
                yamlGateKeys, isolatedGateKeys, codeGateKeys, bindingExclusions, beGatedKeys);

        if (violations.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("棚卸し台帳の gate_key と FE route 束縛表(GATE_ROUTE_MAP)が整合していません。\n")
                .append("本番人の検出を緩めて通すことは禁止。台帳側か束縛表側を直すこと。\n")
                .append("違反一覧:\n");
        for (String v : violations) {
            sb.append("  x ").append(v).append("\n");
        }
        assertThat(violations).as(sb.toString()).isEmpty();
    }


    /**
     * 違反検出の中核（純関数）。合成入力で本番人自身の偽陰性を暴けるように切り出してある。
     *
     * @param yamlGateKeys     台帳に存在する gate_key の全集合
     * @param isolatedGateKeys 台帳で隔離対象かつ gate_key を持つ行（gate_key -> feature_key）
     * @param codeGateKeys     GATE_ROUTE_MAP から抽出した gate_key（重複を保持した出現順リスト）
     */
    static List<String> detectViolations(
            Set<String> yamlGateKeys,
            Map<String, String> isolatedGateKeys,
            List<String> codeGateKeys) {
        return detectViolations(yamlGateKeys, isolatedGateKeys, codeGateKeys, Map.of());
    }

    /**
     * 違反検出の中核（純関数・route 束縛の明示除外つき）。
     *
     * @param bindingExclusions 台帳 {@code route_binding_exclusions} の宣言（gate_key -> reason）。
     *                          <b>専用の URL を1本も持たない BE 専用ゲート</b>だけが対象で、
     *                          理由必須・陳腐化検出つき（下記 (iv)〜(vi)）。
     */
    static List<String> detectViolations(
            Set<String> yamlGateKeys,
            Map<String, String> isolatedGateKeys,
            List<String> codeGateKeys,
            Map<String, String> bindingExclusions) {
        // (vii) を問わない呼び出し（(iv)〜(vi) の自己検証用）。全除外キーに BE ゲートがある前提を置く。
        return detectViolations(yamlGateKeys, isolatedGateKeys, codeGateKeys,
                bindingExclusions, bindingExclusions.keySet());
    }

    /**
     * 違反検出の中核（純関数・route 束縛の明示除外と BE ゲート照合つき）。
     *
     * @param beGatedKeys 本番コードの {@code @RequireFeature} に実在するフラグキー
     *                    （{@link FeatureGateAnnotationKeyGuardTest#annotatedFeatureKeys()}）。
     *                    除外宣言が台帳規約 7-b の適用条件
     *                    「<b>BE 側だけをゲートで塞ぐ機能</b>」に収まっているかを (vii) で照合する。
     */
    static List<String> detectViolations(
            Set<String> yamlGateKeys,
            Map<String, String> isolatedGateKeys,
            List<String> codeGateKeys,
            Map<String, String> bindingExclusions,
            Set<String> beGatedKeys) {

        List<String> violations = new ArrayList<>();

        // (iii) コード側の重複
        Map<String, Integer> codeOccurrences = new LinkedHashMap<>();
        for (String key : codeGateKeys) {
            codeOccurrences.merge(key, 1, Integer::sum);
        }
        codeOccurrences.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .forEach(e -> violations.add("(iii) GATE_ROUTE_MAP で gate_key が重複している: "
                        + e.getKey() + "（" + e.getValue() + "回出現）"));

        // (i) コード側にあり台帳に無い
        for (String key : codeOccurrences.keySet()) {
            if (!yamlGateKeys.contains(key)) {
                violations.add("(i) GATE_ROUTE_MAP の gate_key が棚卸し台帳に存在しない: " + key
                        + " — gate_key の唯一の発行元は docs/inventory/feature-inventory.yaml の"
                        + " release.gate_key である。綴り間違いは全ユーザーへの機能封鎖として現れる");
            }
        }

        // (ii) 台帳で隔離対象かつ gate_key ありなのにコード側に束縛が無い
        for (Map.Entry<String, String> e : isolatedGateKeys.entrySet()) {
            if (codeOccurrences.containsKey(e.getKey()) || bindingExclusions.containsKey(e.getKey())) {
                continue;
            }
            violations.add("(ii) 隔離対象（release.beta が β限定/内部限定/停止）の gate_key に"
                    + " route 束縛が無い: " + e.getKey()
                    + "（feature_key=" + e.getValue() + "）"
                    + " — frontend/app/constants/featureGates.ts の GATE_ROUTE_MAP に"
                    + " URL パスプレフィクスを追加するか、専用 URL を1本も持たない BE 専用ゲートなら"
                    + " 台帳の route_binding_exclusions に理由付きで宣言すること");
        }

        // (iv) 除外宣言の理由が空（無審査で膨らむ baseline にしない）
        for (Map.Entry<String, String> e : bindingExclusions.entrySet()) {
            String reason = e.getValue();
            if (reason == null || reason.isBlank()) {
                violations.add("(iv) route_binding_exclusions の宣言に reason が無い: " + e.getKey()
                        + " — 束縛しない理由をその場に読める形で書くこと");
            }
        }

        // (v) 除外宣言したのに実際は束縛済み（陳腐化。除外を消すべき状態）
        for (String key : bindingExclusions.keySet()) {
            if (codeOccurrences.containsKey(key)) {
                violations.add("(v) route_binding_exclusions に宣言があるのに GATE_ROUTE_MAP にも束縛がある: "
                        + key + " — 束縛できたなら除外宣言を削除すること（陳腐化した除外は穴の温床になる）");
            }
        }

        // (vi) 除外宣言の対象が隔離対象の gate_key として台帳に実在しない（陳腐化・綴り間違い）
        for (String key : bindingExclusions.keySet()) {
            if (!isolatedGateKeys.containsKey(key)) {
                violations.add("(vi) route_binding_exclusions の gate_key が"
                        + "「隔離対象かつ gate_key 発行済み」の台帳行に対応しない: " + key
                        + " — 綴り間違いか、機能が隔離対象でなくなった（除外宣言を削除すること）");
            }
        }

        // (vii) 除外宣言の対象に BE 側のゲート（@RequireFeature）が無い
        //
        // 規約 7-b の適用条件は「BE 側だけをゲートで塞ぐ（＝専用 URL は無いが @RequireFeature はある）」
        // 機能に限る、というものである。(iv)〜(vi) は理由・陳腐化・綴りしか見ておらず、
        // この条件そのものを検証していなかった（Codex 検分 P2）。照合が無いと
        // 「理由さえ書けば何でも除外できる」状態になり、FE も BE も未隔離のまま
        // 番人が green になる恒久的な抜け道が残る。
        for (String key : bindingExclusions.keySet()) {
            if (!beGatedKeys.contains(key)) {
                violations.add("(vii) route_binding_exclusions の gate_key に対応する BE ゲートが無い: "
                        + key
                        + " — route_binding_exclusions は「専用 URL を持たないが"
                        + " @RequireFeature で BE 入口を塞いでいる」機能に限る（台帳の規約 7-b）。"
                        + " 実装クラスに @RequireFeature(\"" + key + "\") を付けるか、"
                        + " route 層で塞ぐべき機能なら GATE_ROUTE_MAP に束縛すること。"
                        + " FE も BE も塞がない機能を除外宣言で通してはならない");
            }
        }

        return violations;
    }

    // ------------------------------------------------------------------
    // 本番人自身の検出力の実証（AC-9）。合成入力で3ケースが必ず違反として挙がることを固定する。
    // これが無いと「検出器が何も検出できないまま緑」という偽陰性に気付けない。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自己検証: 整合した合成入力では違反が出ないこと（偽陽性が無い）")
    void 整合入力では違反なし() {
        List<String> violations = detectViolations(
                Set.of("SHIFT", "MARKET"),
                Map.of("SHIFT", "shift", "MARKET", "market"),
                List.of("SHIFT", "MARKET"));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("自己検証(i): コード側にあり台帳に無い gate_key を検出すること")
    void 検出ケース_i_コードにあり台帳に無い() {
        List<String> violations = detectViolations(
                Set.of("SHIFT"),
                Map.of("SHIFT", "shift"),
                List.of("SHIFT", "SHFIT")); // 綴り間違い

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("(i)").contains("SHFIT");
    }

    @Test
    @DisplayName("自己検証(ii): 隔離対象で gate_key ありなのに route 束縛が無いものを検出すること")
    void 検出ケース_ii_隔離対象の束縛漏れ() {
        List<String> violations = detectViolations(
                Set.of("SHIFT", "MARKET"),
                Map.of("SHIFT", "shift", "MARKET", "market"),
                List.of("SHIFT")); // MARKET の束縛が無い

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("(ii)").contains("MARKET").contains("market");
    }

    @Test
    @DisplayName("自己検証(iii): コード側の gate_key 重複を検出すること")
    void 検出ケース_iii_コード側の重複() {
        List<String> violations = detectViolations(
                Set.of("SHIFT"),
                Map.of("SHIFT", "shift"),
                List.of("SHIFT", "SHIFT"));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("(iii)").contains("SHIFT");
    }


    @Test
    @DisplayName("自己検証: 理由付きの route_binding_exclusions は (ii) を免除する（BE 専用ゲート）")
    void 除外宣言は束縛漏れを免除する() {
        List<String> violations = detectViolations(
                Set.of("SHIFT", "AUTO_ASSIGN"),
                Map.of("SHIFT", "shift", "AUTO_ASSIGN", "shift-auto-assign"),
                List.of("SHIFT"),
                Map.of("AUTO_ASSIGN", "専用 URL を持たない BE 専用ゲートのため"));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("自己検証(iv): 理由が空の除外宣言を検出すること")
    void 検出ケース_iv_理由なしの除外宣言() {
        List<String> violations = detectViolations(
                Set.of("SHIFT", "AUTO_ASSIGN"),
                Map.of("SHIFT", "shift", "AUTO_ASSIGN", "shift-auto-assign"),
                List.of("SHIFT"),
                Map.of("AUTO_ASSIGN", "   "));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("(iv)").contains("AUTO_ASSIGN");
    }

    @Test
    @DisplayName("自己検証(v): 束縛済みなのに残っている除外宣言（陳腐化）を検出すること")
    void 検出ケース_v_陳腐化した除外宣言() {
        List<String> violations = detectViolations(
                Set.of("SHIFT"),
                Map.of("SHIFT", "shift"),
                List.of("SHIFT"),
                Map.of("SHIFT", "もう束縛済みなので不要"));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("(v)").contains("SHIFT");
    }

    @Test
    @DisplayName("自己検証(vi): 台帳に対応の無い除外宣言（綴り間違い・隔離対象外）を検出すること")
    void 検出ケース_vi_台帳に無い除外宣言() {
        List<String> violations = detectViolations(
                Set.of("SHIFT"),
                Map.of("SHIFT", "shift"),
                List.of("SHIFT"),
                Map.of("SHFIT", "綴り間違い"));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("(vi)").contains("SHFIT");
    }

    @Test
    @DisplayName("自己検証(vii): BE ゲート（@RequireFeature）を持たない除外宣言を検出すること")
    void 検出ケース_vii_BEゲートなしの除外宣言() {
        // 理由も書いてあり台帳にも実在する（(iv)〜(vi) はすべて素通りする）除外宣言でも、
        // BE 側に @RequireFeature が無ければ FE・BE とも未隔離であり、通してはならない。
        List<String> violations = detectViolations(
                Set.of("SHIFT", "AUTO_ASSIGN"),
                Map.of("SHIFT", "shift", "AUTO_ASSIGN", "shift-auto-assign"),
                List.of("SHIFT"),
                Map.of("AUTO_ASSIGN", "専用 URL が無いので束縛しない（と称しているだけ）"),
                Set.of("SHIFT")); // AUTO_ASSIGN は @RequireFeature に実在しない

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("(vii)").contains("AUTO_ASSIGN");
    }

    @Test
    @DisplayName("自己検証(vii): BE ゲートを持つ除外宣言は (ii) を免除され違反にならない（偽陽性が無い）")
    void 正例_BEゲートありの除外宣言は通る() {
        List<String> violations = detectViolations(
                Set.of("SHIFT", "AUTO_ASSIGN"),
                Map.of("SHIFT", "shift", "AUTO_ASSIGN", "shift-auto-assign"),
                List.of("SHIFT"),
                Map.of("AUTO_ASSIGN", "専用 URL を持たない BE 専用ゲートのため"),
                Set.of("SHIFT", "AUTO_ASSIGN"));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("裏取り: 実台帳の除外宣言が BE ゲートに実在するキーだけであること")
    void 実台帳の除外宣言はBEゲートを持つ() throws IOException {
        Path yamlPath = resolveFromRepoRoot(FEATURE_INVENTORY_RELATIVE);
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(yamlPath)) {
            root = new Yaml().load(in);
        }
        Map<String, String> exclusions = parseRouteBindingExclusions(root);
        Set<String> beGatedKeys = FeatureGateAnnotationKeyGuardTest.annotatedFeatureKeys();

        assertThat(beGatedKeys)
                .as("本番コードの @RequireFeature を1件も収集できていない（照合経路の破損）")
                .isNotEmpty();
        assertThat(beGatedKeys)
                .as("route_binding_exclusions の宣言はすべて BE の @RequireFeature に実在すること")
                .containsAll(exclusions.keySet());
    }

    /**
     * 台帳トップレベルの {@code route_binding_exclusions}（gate_key -> reason）を読む。
     *
     * <p>要素が不正な形でも例外にせず空文字の reason として返し、(iv) で違反として現れるようにする
     * （パース経路で黙って落とすと除外が無審査で通ってしまうため）。</p>
     */
    static Map<String, String> parseRouteBindingExclusions(Map<String, Object> root) {
        Map<String, String> result = new LinkedHashMap<>();
        Object obj = root == null ? null : root.get("route_binding_exclusions");
        if (!(obj instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object gateKey = map.get("gate_key");
            if (gateKey == null) {
                continue;
            }
            Object reason = map.get("reason");
            result.put(String.valueOf(gateKey), reason == null ? "" : String.valueOf(reason));
        }
        return result;
    }

    /** {@code GATE_ROUTE_MAP} の全プレフィクス（配列要素）を宣言順に抽出する。 */
    static List<String> extractPrefixes(String ts) {
        Matcher block = GATE_ROUTE_MAP_BLOCK.matcher(ts);
        List<String> prefixes = new ArrayList<>();
        if (!block.find()) return prefixes;
        Matcher entry = Pattern.compile("(?m)^\\s{2}([A-Z][A-Z0-9_]*)\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(block.group(1));
        while (entry.find()) {
            Matcher lit = Pattern.compile("'([^']+)'").matcher(entry.group(2));
            while (lit.find()) {
                prefixes.add(lit.group(1));
            }
        }
        return prefixes;
    }

    /** db/migration 配下の SQL から feature_flags に seed されている flag_key を集める。 */
    static Set<String> seededFlagKeys(Path migrationDir) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        if (!Files.isDirectory(migrationDir)) return keys;
        try (java.util.stream.Stream<Path> walk = Files.walk(migrationDir)) {
            for (Path f : walk.filter(Files::isRegularFile)
                    .filter(x -> x.getFileName().toString().endsWith(".sql")).toList()) {
                String sql = Files.readString(f, StandardCharsets.UTF_8);
                if (!sql.toLowerCase(java.util.Locale.ROOT).contains("feature_flags")) continue;
                Matcher m = Pattern.compile("'(FEATURE_[A-Z0-9_]+)'").matcher(sql);
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys;
    }

    @Test
    @DisplayName("GATE_ROUTE_MAP の gate_key が feature_flags に seed 済みであること（恒久 deny 防止）")
    void gateKeyに対応するフラグ行が実在する() throws IOException {
        Path tsPath = resolveFromRepoRoot(FEATURE_GATES_TS_RELATIVE);
        Path migrationDir = resolveFromRepoRoot(
                Paths.get("backend", "src", "main", "resources", "db", "migration"));

        assertThat(Files.isRegularFile(tsPath)).as("route 束縛表が見つからない: " + tsPath).isTrue();
        assertThat(Files.isDirectory(migrationDir))
                .as("Flyway migration ディレクトリが見つからない: " + migrationDir).isTrue();

        String ts = Files.readString(tsPath, StandardCharsets.UTF_8);
        Matcher blockMatcher = GATE_ROUTE_MAP_BLOCK.matcher(ts);
        assertThat(blockMatcher.find()).as("GATE_ROUTE_MAP を切り出せなかった").isTrue();
        Matcher entryMatcher = GATE_KEY_ENTRY.matcher(blockMatcher.group(1));

        Set<String> seeded = seededFlagKeys(migrationDir);
        List<String> violations = new ArrayList<>();

        while (entryMatcher.find()) {
            String gateKey = entryMatcher.group(1);
            if (!gateKey.matches("FEATURE_[A-Z0-9_]+_ENABLED")) {
                violations.add("gate_key が既存の flag_key 命名規約 FEATURE_{NAME}_ENABLED に従っていない: "
                        + gateKey + " — gate_key は feature_flags.flag_key と同一文字列である必要がある");
            }
            if (!seeded.contains(gateKey)) {
                violations.add("gate_key に対応する feature_flags の行が seed されていない: " + gateKey
                        + " — 行が無いキーは isEnabled() の `?? false` で恒久 deny になり、"
                        + "しかも管理コンソールの PUT /{flagKey} が findByFlagKey で 404 になるため"
                        + "ON にする手段も無い。db/migration に seed を追加すること");
            }
        }

        if (violations.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("route ガードの gate_key とフィーチャーフラグ実体が対応していません。\n")
                .append("違反一覧:\n");
        for (String v : violations) sb.append("  x ").append(v).append("\n");
        assertThat(violations).as(sb.toString()).isEmpty();
    }

    /**
     * backend/ 実行・リポジトリルート実行の両方に対応してリポジトリ相対パスを解決する。
     *
     * <p>ファイルだけでなく<b>ディレクトリも解決対象</b>にする（FE のページディレクトリや
     * Flyway migration ディレクトリを渡すため。isRegularFile だけで判定すると
     * ディレクトリが常に未解決になり、番人が「見つからない」で落ちる）。</p>
     */
    static Path resolveFromRepoRoot(Path relative) {
        for (Path candidate : new Path[]{
                relative,
                Paths.get("..").resolve(relative),
                Paths.get("backend").resolve(relative),
        }) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return relative; // 見つからなければそのまま返し、テスト内で存在チェックに失敗させる
    }
}
