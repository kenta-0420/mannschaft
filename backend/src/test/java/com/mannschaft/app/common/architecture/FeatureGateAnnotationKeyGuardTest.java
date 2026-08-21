package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: {@code @RequireFeature} の引数が「文字列リテラル」かつ「実在するフラグキー」であること
 * （Gate 基盤工事③ / 受け入れ条件 AC-9）。
 *
 * <h2>なぜ番人が要るのか</h2>
 * <p>{@code FeatureFlagService.isEnabled(flagKey)} は <b>行が無いキーに対して false を返す</b>
 * （{@code orElse(false)} のフェイルクローズ）。したがって {@code @RequireFeature} に
 * 綴りを間違えたキーを書くと、コンパイルも通り、テストも（そのフラグを stub すれば）通り、
 * <b>本番だけが全ユーザーに対して恒久 deny になる</b>。しかも管理コンソールの
 * {@code PUT /api/v1/system-admin/feature-flags/{flagKey}} は {@code findByFlagKey} が
 * 404 になるため、後から ON にする手段すら無い。この事故は静的にしか捕まえられない。</p>
 *
 * <p>また、キーを定数参照（{@code Flags.SHIFT}）で書くと付与箇所を読んだだけでは
 * 何を要求しているか分からず、本番人も台帳との照合ができなくなる。よって
 * <b>文字列リテラル必須</b>とする（金型: {@code BatchMarkerAnnotationGuardTest} の
 * 「理由がその場に読めること」と同じ思想）。<b>免除リストは設けない</b>。</p>
 *
 * <h2>実在判定の突き合わせ先 — seed が唯一の正</h2>
 * <p>「実在する」の判定基準は {@code backend/src/main/resources/db/migration} の
 * {@code feature_flags} seed に<b>行が存在すること</b>のみである。
 * 棚卸し台帳 {@code docs/inventory/feature-inventory.yaml} の {@code release.gate_key}
 * は gate_key の唯一の発行元ではあるが、台帳に載っているだけでは
 * {@code FeatureFlagService.isEnabled} が見る {@code feature_flags} テーブルに行が無く、
 * <b>台帳にしか無いキーを指定すると CI は緑のまま本番だけ恒久 deny になる</b>
 * （実測: 台帳の非 null な gate_key 18件のうち {@code PERSONAL_PROFILE} だけが
 * feature_flags seed に存在しない。Codex 検分指摘②）。
 * よって「台帳 ∪ seed」の和集合で許可してはならず、<b>seed 集合への所属を必須</b>とする。</p>
 *
 * <h2>走査対象は src/main のみ</h2>
 * <p>テストコードは AOP 検証用のダミーキー（{@code FEATURE_NO_SUCH_ROW_ENABLED} 等）を
 * 意図的に使うため走査しない。本番コードに書かれたキーだけが本番の deny を生む。</p>
 *
 * <h2>空虚 green の防止</h2>
 * <p>本番人の発足時点では {@code @RequireFeature} の付与箇所が 0 件であり、
 * パーサが壊れていても実ファイル走査は緑になる。そこで {@link 判定ロジック自己検証} が
 * 実ファイル走査と<b>同一コア</b>（{@link #analyze}）に合成入力を通し、
 * 負例で違反が返ることを固定する。</p>
 */
@DisplayName("番人: @RequireFeature のキーはリテラル必須かつ実在すること（Gate基盤工事③ AC-9）")
class FeatureGateAnnotationKeyGuardTest {

    private static final Path MAIN_SOURCE_ROOT = Paths.get("src", "main", "java");

    private static final Path FEATURE_INVENTORY_RELATIVE =
            Paths.get("docs", "inventory", "feature-inventory.yaml");

    private static final Path MIGRATION_RELATIVE =
            Paths.get("backend", "src", "main", "resources", "db", "migration");

    /** {@code @RequireFeature( ... )} の引数部分を切り出す。 */
    private static final Pattern ANNOTATION =
            Pattern.compile("@RequireFeature\\s*\\(([^)]*)\\)", Pattern.DOTALL);

    /** 単一の文字列リテラル（{@code "FEATURE_X_ENABLED"}）。 */
    private static final Pattern STRING_LITERAL =
            Pattern.compile("^\"([^\"\\\\]*)\"$");

    // ===================================================================
    // 実ファイル走査
    // ===================================================================

    @Test
    @DisplayName("本番コードの @RequireFeature は全て文字列リテラルかつ実在キーであること")
    void requireFeatureのキーがリテラルかつ実在する() throws IOException {
        Set<String> knownKeys = knownFlagKeys();

        assertThat(knownKeys)
                .as("既知フラグキーを1件も収集できなかった。台帳/seed の読み取り経路が壊れている")
                .isNotEmpty();

        List<String> violations = analyze(loadSources(MAIN_SOURCE_ROOT), knownKeys);

        if (violations.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("@RequireFeature の指定が規約に反しています。\n")
                .append("検出を緩めて通すことは禁止。付与側のキーを直すか、台帳/seed に実体を追加すること。\n")
                .append("違反一覧:\n");
        for (String v : violations) {
            sb.append("  x ").append(v).append("\n");
        }
        assertThat(violations).as(sb.toString()).isEmpty();
    }

    @Test
    @DisplayName("走査が空振りしていないこと（ソースを1件も読めていない状態での空虚 green 防止）")
    void 走査対象のソースを実際に読めていること() throws IOException {
        List<Source> sources = loadSources(MAIN_SOURCE_ROOT);
        assertThat(sources.size())
                .as("本番ソースの走査件数が少なすぎる（CWD またはソースルートの想定が崩れている）: "
                        + MAIN_SOURCE_ROOT.toAbsolutePath())
                .isGreaterThan(500);
    }

    @Test
    @DisplayName("陽性対照: 台帳にしか無いキー（PERSONAL_PROFILE）は既知扱いされず番人に落とされる（検分指摘②）")
    void 陽性対照_台帳のみのキーは既知扱いされない() throws IOException {
        Set<String> knownKeys = knownFlagKeys();

        assertThat(inventoryGateKeys())
                .as("この陽性対照は PERSONAL_PROFILE が台帳側に実在することが前提")
                .contains("PERSONAL_PROFILE");
        assertThat(knownKeys)
                .as("PERSONAL_PROFILE は feature_flags seed に行が無いため、"
                        + "台帳との和集合を取っていれば既知扱いされてしまう。"
                        + "seed 集合を必須にした是正後は既知扱いされてはならない")
                .doesNotContain("PERSONAL_PROFILE");

        List<String> violations = analyze(List.of(new Source("Synthetic.java",
                "class Synthetic {\n"
                        + "  @RequireFeature(\"PERSONAL_PROFILE\")\n"
                        + "  void a() {}\n"
                        + "}\n")), knownKeys);

        assertThat(violations)
                .as("台帳にしかないキー PERSONAL_PROFILE を指定した検体は番人に落とされなければならない"
                        + "（落ちなければ CI 緑のまま本番で恒久 deny になる）")
                .hasSize(1);
        assertThat(violations.get(0)).contains("実在しないフラグキー").contains("PERSONAL_PROFILE");
    }

    @Test
    @DisplayName("裏取り: 台帳と seed の双方から既知フラグキーを収集できていること")
    void 既知フラグキーを台帳とseedの双方から収集できている() throws IOException {
        assertThat(inventoryGateKeys())
                .as("棚卸し台帳の release.gate_key を1件も読めていない（パース経路の破損）")
                .isNotEmpty();
        assertThat(FeatureGateRouteMapGuardTest.seededFlagKeys(
                FeatureGateRouteMapGuardTest.resolveFromRepoRoot(MIGRATION_RELATIVE)))
                .as("db/migration の feature_flags seed を1件も読めていない（パース経路の破損）")
                .isNotEmpty();
    }

    // ===================================================================
    // 判定コア（純関数。合成入力で偽陰性を暴けるように切り出してある）
    // ===================================================================

    /**
     * ソース群から {@code @RequireFeature} を抽出し、規約違反を列挙する。
     *
     * @param sources   走査対象ソース
     * @param knownKeys 実在すると認めるフラグキーの集合
     * @return 違反一覧（空なら合格）
     */
    static List<String> analyze(List<Source> sources, Set<String> knownKeys) {
        List<String> violations = new ArrayList<>();
        for (Source src : sources) {
            if (!src.content.contains("@RequireFeature")) {
                continue;
            }
            Matcher m = ANNOTATION.matcher(src.content);
            while (m.find()) {
                String args = m.group(1).strip();
                int line = lineOf(src.content, m.start());
                String where = src.relPath + ":" + line;

                if (args.isEmpty()) {
                    violations.add(where + " — @RequireFeature の value() が空である");
                    continue;
                }
                String body = args;
                if (body.startsWith("{") && body.endsWith("}")) {
                    body = body.substring(1, body.length() - 1);
                }
                List<String> tokens = new ArrayList<>();
                for (String t : body.split(",")) {
                    if (!t.isBlank()) {
                        tokens.add(t.strip());
                    }
                }
                if (tokens.isEmpty()) {
                    violations.add(where + " — @RequireFeature の value() が空配列である");
                    continue;
                }
                for (String token : tokens) {
                    Matcher lit = STRING_LITERAL.matcher(token);
                    if (!lit.matches()) {
                        violations.add(where + " — キーが文字列リテラルでない: " + token
                                + "（定数参照は付与箇所を読んだだけで要求が分からず、"
                                + "台帳との照合もできない）");
                        continue;
                    }
                    String key = lit.group(1);
                    if (!knownKeys.contains(key)) {
                        violations.add(where + " — 実在しないフラグキー: " + key
                                + "（feature_flags に行が無いキーは isEnabled() が false を返し"
                                + "全ユーザー恒久 deny になる。しかも管理コンソールの"
                                + " PUT /{flagKey} が 404 で ON にする手段も無い。"
                                + " docs/inventory/feature-inventory.yaml の release.gate_key と"
                                + " db/migration の seed を確認せよ）");
                    }
                }
            }
        }
        return violations;
    }

    // ===================================================================
    // 判定ロジック自己検証（実ファイルと同一コアに合成入力を通す）
    // ===================================================================

    @Nested
    @DisplayName("判定ロジック自己検証")
    class 判定ロジック自己検証 {

        private final Set<String> known = Set.of("FEATURE_SHIFT_ENABLED", "FEATURE_MARKET_ENABLED");

        private Source src(String body) {
            return new Source("Synthetic.java", "class Synthetic {\n" + body + "\n}\n");
        }

        @Test
        @DisplayName("正例: リテラルかつ実在キーなら違反なし（偽陽性が無い）")
        void 正例では違反なし() {
            assertThat(analyze(List.of(
                    src("  @RequireFeature(\"FEATURE_SHIFT_ENABLED\")\n  void a() {}"),
                    src("  @RequireFeature({\"FEATURE_SHIFT_ENABLED\", \"FEATURE_MARKET_ENABLED\"})\n  void b() {}")
            ), known)).isEmpty();
        }

        @Test
        @DisplayName("負例(i): 定数参照は文字列リテラルでないとして検出する")
        void 負例_定数参照を検出する() {
            List<String> v = analyze(List.of(
                    src("  @RequireFeature(Flags.SHIFT)\n  void a() {}")), known);

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("文字列リテラルでない").contains("Flags.SHIFT");
        }

        @Test
        @DisplayName("負例(ii): 実在しないキー（綴り間違い）を検出する")
        void 負例_実在しないキーを検出する() {
            List<String> v = analyze(List.of(
                    src("  @RequireFeature(\"FEATURE_SHFIT_ENABLED\")\n  void a() {}")), known);

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("実在しないフラグキー").contains("FEATURE_SHFIT_ENABLED");
        }

        @Test
        @DisplayName("負例(iii): 複数指定のうち片方だけが実在しない場合も検出する")
        void 負例_複数指定の片方が実在しない() {
            List<String> v = analyze(List.of(
                    src("  @RequireFeature({\"FEATURE_SHIFT_ENABLED\", \"FEATURE_UNKNOWN_ENABLED\"})\n  void a() {}")),
                    known);

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("FEATURE_UNKNOWN_ENABLED");
        }

        @Test
        @DisplayName("負例(iv): value() が空の付与を検出する")
        void 負例_空指定を検出する() {
            List<String> v = analyze(List.of(
                    src("  @RequireFeature({})\n  void a() {}")), known);

            assertThat(v).hasSize(1);
            assertThat(v.get(0)).contains("空配列");
        }
    }

    // ===================================================================
    // 既知フラグキーの収集
    // ===================================================================

    /**
     * 実在すると認める既知フラグキー集合。
     *
     * <p>{@code db/migration} の {@code feature_flags} seed のみを正とする（seed 集合必須）。
     * 台帳の gate_key との和集合は取らない — 台帳にしか無いキーは
     * {@code feature_flags} に行が無く、isEnabled() が恒久 false を返すため
     * （Codex 検分指摘②。台帳との整合自体は
     * {@link #既知フラグキーを台帳とseedの双方から収集できている()} で別途裏取りする）。</p>
     */
    static Set<String> knownFlagKeys() throws IOException {
        return new LinkedHashSet<>(FeatureGateRouteMapGuardTest.seededFlagKeys(
                FeatureGateRouteMapGuardTest.resolveFromRepoRoot(MIGRATION_RELATIVE)));
    }

    /** 棚卸し台帳の {@code release.gate_key}（null 以外）を集める。 */
    static Set<String> inventoryGateKeys() throws IOException {
        Path yamlPath = FeatureGateRouteMapGuardTest.resolveFromRepoRoot(FEATURE_INVENTORY_RELATIVE);
        assertThat(Files.isRegularFile(yamlPath))
                .as("棚卸し台帳が見つからない: " + yamlPath.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）")
                .isTrue();

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(yamlPath)) {
            root = new Yaml().load(in);
        }
        assertThat(root).as("棚卸し台帳のパースに失敗した").isNotNull();

        Set<String> keys = new LinkedHashSet<>();
        Object recordsObj = root.get("records");
        if (!(recordsObj instanceof List<?> records)) {
            return keys;
        }
        for (Object recordObj : records) {
            if (!(recordObj instanceof Map<?, ?> record)) {
                continue;
            }
            if (!(record.get("release") instanceof Map<?, ?> release)) {
                continue;
            }
            Object gateKey = release.get("gate_key");
            if (gateKey != null) {
                keys.add(String.valueOf(gateKey));
            }
        }
        return keys;
    }

    // ===================================================================
    // ファイル読み込み・小道具
    // ===================================================================

    /** 走査対象ソース1件（リポジトリ相対パスと本文）。 */
    record Source(String relPath, String content) {
    }

    private static List<Source> loadSources(Path root) throws IOException {
        assertThat(Files.isDirectory(root))
                .as("ソースルートが見つからない: " + root.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）")
                .isTrue();
        List<Source> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            out.add(new Source(p.toString().replace('\\', '/'),
                                    Files.readString(p, StandardCharsets.UTF_8)));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return out;
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
