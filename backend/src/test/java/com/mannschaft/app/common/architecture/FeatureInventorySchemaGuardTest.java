package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: {@code docs/inventory/feature-inventory.yaml}（β機能棚卸し台帳の正本）が
 * スキーマ規約に従っていること。
 *
 * <h2>本テストが守るもの</h2>
 * <ul>
 *   <li>YAML がパース可能であり、records が非空であること</li>
 *   <li>feature_key が kebab-case かつ一意であること（AC-4）</li>
 *   <li>実装状態・検証状態・β区分・デプロイ安全性・layer・検証軸キーが定義済み語彙のみであること
 *       （表記揺れ・英語値の混入を防ぐ, AC-2）</li>
 *   <li>layer=能力 の行に implementation が存在すること</li>
 *   <li>verification の各軸 status が「通過」の場合、evidence が非空であること（AC-3）</li>
 *   <li>「不明」は常に合法であること（AC-5, 明示的にはねない条件を持たない = 通す）</li>
 *   <li>gate_key（あれば）が SCREAMING_SNAKE かつ一意であること</li>
 * </ul>
 *
 * <h2>本テストは ArchUnit ではない</h2>
 * <p>Markdown/YAML ドキュメントの走査であり、ソースコードのバイトコード解析ではないため、
 * {@link TaskListCmpIdDuplicateGuardTest} と同じ<b>ソース（ドキュメント）走査型</b>で書いた。
 * ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）は一切使わない。</p>
 *
 * <h2>本番人の検出を緩めて通すこと（語彙の追加緩和・evidence必須ルールの撤廃等）は禁止</h2>
 * <p>台帳の信頼性は語彙の一貫性と証拠の紐付けに依存する。違反が見つかった場合は
 * {@code docs/inventory/feature-inventory.yaml} 側を修正すること。</p>
 */
@DisplayName("番人: docs/inventory/feature-inventory.yaml のスキーマ検証")
class FeatureInventorySchemaGuardTest {

    private static final Path FEATURE_INVENTORY_RELATIVE =
            Paths.get("docs", "inventory", "feature-inventory.yaml");

    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern SCREAMING_SNAKE = Pattern.compile("^[A-Z0-9]+(_[A-Z0-9]+)*$");

    private static final Set<String> IMPLEMENTATION_VALUES =
            Set.of("未実装", "部分実装", "実装済", "不具合あり", "不明", "対象外");

    private static final Set<String> VERIFICATION_STATUS_VALUES =
            Set.of("対象外", "未作成", "未実行", "失敗中", "通過", "ブロック中", "不明");

    private static final Set<String> BETA_VALUES =
            Set.of("コア", "β限定", "内部限定", "停止");

    private static final Set<String> DEPLOY_SAFETY_VALUES =
            Set.of("安全", "条件付き安全", "デプロイ阻害", "不明");

    private static final Set<String> LAYER_VALUES = Set.of("能力", "ドメイン");

    private static final Set<String> IMPLEMENTATION_COMPONENT_KEYS =
            Set.of("frontend", "backend", "database", "background");

    private static final Set<String> VERIFICATION_AXIS_KEYS = Set.of(
            "unit", "integration", "authorization", "migration", "real_e2e",
            "human_ui", "resident_test", "external_service", "load", "operation");

    @Test
    @DisplayName("スキーマ規約（語彙・一意性・evidence必須ルール）に違反しないこと")
    void featureInventoryはスキーマに従う() throws IOException {
        Path path = resolveFeatureInventoryPath();

        assertThat(Files.isRegularFile(path))
                .as("docs/inventory/feature-inventory.yaml が見つからない: " + path.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）。"
                        + "リポジトリ構成が変わった場合は resolveFeatureInventoryPath() の候補パスを見直すこと。")
                .isTrue();

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            Yaml yaml = new Yaml();
            root = yaml.load(in);
        }

        assertThat(root)
                .as("feature-inventory.yaml のパースに失敗した（トップレベルが null または非マップ）")
                .isNotNull();

        Object recordsObj = root.get("records");
        assertThat(recordsObj)
                .as("feature-inventory.yaml に records キーが存在しない")
                .isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<Object> records = (List<Object>) recordsObj;

        assertThat(records)
                .as("records が空である。β機能棚卸し台帳には最低1件のレコードが必要")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        Map<String, Integer> featureKeyOccurrences = new LinkedHashMap<>();
        Map<String, Integer> gateKeyOccurrences = new LinkedHashMap<>();

        for (Object recordObj : records) {
            if (!(recordObj instanceof Map)) {
                violations.add("records の要素がマップでない: " + recordObj);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> record = (Map<String, Object>) recordObj;

            String featureKey = asString(record.get("feature_key"));
            String recordLabel = featureKey != null ? featureKey : "(feature_key不明)";

            if (featureKey == null || featureKey.isBlank()) {
                violations.add(recordLabel + ": feature_key が欠落している");
            } else {
                if (!KEBAB_CASE.matcher(featureKey).matches()) {
                    violations.add(featureKey + ": feature_key が kebab-case ではない");
                }
                featureKeyOccurrences.merge(featureKey, 1, Integer::sum);
            }

            String layer = asString(record.get("layer"));
            if (layer == null || !LAYER_VALUES.contains(layer)) {
                violations.add(recordLabel + ": layer が語彙外またはが欠落している: " + layer);
            }

            Object implementationObj = record.get("implementation");
            if ("能力".equals(layer)) {
                if (!(implementationObj instanceof Map) || ((Map<?, ?>) implementationObj).isEmpty()) {
                    violations.add(recordLabel + ": layer=能力 だが implementation が欠落している");
                }
            }

            if (implementationObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> implementation = (Map<String, Object>) implementationObj;
                boolean hasImplemented = false;
                for (Map.Entry<String, Object> e : implementation.entrySet()) {
                    String key = e.getKey();
                    if (!IMPLEMENTATION_COMPONENT_KEYS.contains(key)) {
                        violations.add(recordLabel + ": implementation の未知キー: " + key);
                        continue;
                    }
                    String value = asString(e.getValue());
                    if (value == null || !IMPLEMENTATION_VALUES.contains(value)) {
                        violations.add(recordLabel + ": implementation." + key + " が語彙外: " + value);
                    } else if ("実装済".equals(value)) {
                        hasImplemented = true;
                    }
                }
                // 実装済の evidence は不要（コード自体が証拠のため）。hasImplemented は将来拡張用に保持。
                boolean unused = hasImplemented;
            }

            Object verificationObj = record.get("verification");
            if (verificationObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> verification = (Map<String, Object>) verificationObj;
                for (Map.Entry<String, Object> axisEntry : verification.entrySet()) {
                    String axisKey = axisEntry.getKey();
                    if (!VERIFICATION_AXIS_KEYS.contains(axisKey)) {
                        violations.add(recordLabel + ": verification の未知の検証軸キー: " + axisKey);
                        continue;
                    }
                    Object axisValueObj = axisEntry.getValue();
                    if (!(axisValueObj instanceof Map)) {
                        violations.add(recordLabel + ": verification." + axisKey + " がマップでない");
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> axisValue = (Map<String, Object>) axisValueObj;
                    String status = asString(axisValue.get("status"));
                    if (status == null || !VERIFICATION_STATUS_VALUES.contains(status)) {
                        violations.add(recordLabel + ": verification." + axisKey + ".status が語彙外: " + status);
                    }
                    Object evidenceObj = axisValue.get("evidence");
                    List<?> evidence = evidenceObj instanceof List ? (List<?>) evidenceObj : List.of();
                    if ("通過".equals(status) && evidence.isEmpty()) {
                        violations.add(recordLabel + ": verification." + axisKey
                                + " が status=通過 なのに evidence が空である");
                    }
                }
            }

            Object releaseObj = record.get("release");
            if (releaseObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> release = (Map<String, Object>) releaseObj;

                String beta = asString(release.get("beta"));
                if (beta == null || !BETA_VALUES.contains(beta)) {
                    violations.add(recordLabel + ": release.beta が語彙外: " + beta);
                }

                String deploySafety = asString(release.get("deploy_safety"));
                if (deploySafety == null || !DEPLOY_SAFETY_VALUES.contains(deploySafety)) {
                    violations.add(recordLabel + ": release.deploy_safety が語彙外: " + deploySafety);
                }

                Object gateKeyObj = release.get("gate_key");
                if (gateKeyObj != null) {
                    String gateKey = asString(gateKeyObj);
                    if (gateKey == null || !SCREAMING_SNAKE.matcher(gateKey).matches()) {
                        violations.add(recordLabel + ": release.gate_key が SCREAMING_SNAKE ではない: " + gateKey);
                    } else {
                        gateKeyOccurrences.merge(gateKey, 1, Integer::sum);
                    }
                }
            } else {
                violations.add(recordLabel + ": release が欠落している");
            }
        }

        // feature_key の重複検出（AC-4）
        featureKeyOccurrences.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .forEach(e -> violations.add("feature_key が重複している: " + e.getKey()
                        + "（" + e.getValue() + "回出現）"));

        // gate_key の重複検出
        gateKeyOccurrences.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .forEach(e -> violations.add("gate_key が重複している: " + e.getKey()
                        + "（" + e.getValue() + "回出現）"));

        if (violations.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("docs/inventory/feature-inventory.yaml がスキーマ規約に違反しています。\n")
                .append("本番人の検出を緩めて通す（語彙の追加緩和・evidence必須ルールの撤廃等）ことは禁止。\n")
                .append("違反一覧:\n");
        for (String v : violations) {
            sb.append("  ✗✗ ").append(v).append("\n");
        }
        assertThat(violations).as(sb.toString()).isEmpty();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** {@code docs/inventory/feature-inventory.yaml} を backend/ 実行・リポジトリルート実行の両方に対応して解決する。 */
    private static Path resolveFeatureInventoryPath() {
        for (Path candidate : new Path[]{
                FEATURE_INVENTORY_RELATIVE,
                Paths.get("..").resolve(FEATURE_INVENTORY_RELATIVE),
                Paths.get("backend").resolve(FEATURE_INVENTORY_RELATIVE),
        }) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return FEATURE_INVENTORY_RELATIVE; // 見つからなければそのまま返し、テスト内で存在チェックに失敗させる
    }
}
