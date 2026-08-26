package com.mannschaft.app.common.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #2764 検分是正（欠陥2 の再発防止番人）。
 *
 * <p>{@link java.text.MessageFormat} は {@code '}（アポストロフィ）をエスケープ開始文字として扱うため、
 * {@code {0}} のようなプレースホルダを含む値に単発の {@code '} が混ざると、それ以降が literal 扱いに
 * なり置換が効かなくなる（今回の欠陥2 の実際の原因）。ロットB・C で 6 言語へ翻訳を追加するたびに
 * 同じ事故が再発しうるため、「プレースホルダを含む値」に「奇数個の {@code '}」が含まれる行を
 * 全 {@code messages*.properties} 横断で機械的に検出する。</p>
 *
 * <p><b>自己検証（検出器の偽陰性を晒す）</b>: 本番ファイルの正常データだけで「検出されない」ことを
 * 確認しても、検出ロジック自体が壊れていて何も引っかからない可能性を排除できない。
 * そのため合成の違反文字列を用意し、検出関数が実際に違反として拾うことを別テストで確認する。</p>
 */
@DisplayName("messages*.properties アポストロフィ/MessageFormat 番人")
class MessagesPropertiesApostropheGuardTest {

    /** プレースホルダ（{0}, {1}, ... 任意の MessageFormat 引数）を含むか。 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\d+[,}]|\\{\\d+$");

    /** 本プロジェクトで実際に存在する messages*.properties 一式（backend/src/main/resources 直下）。 */
    private static final List<String> RESOURCE_NAMES = List.of(
            "messages.properties",
            "messages_ja.properties",
            "messages_en.properties",
            "messages_zh.properties",
            "messages_ko.properties",
            "messages_es.properties",
            "messages_de.properties");

    @Test
    @DisplayName("自己検証: 検出ロジックは合成の違反文字列（奇数個の'を含むプレースホルダ値）を実際に検出する")
    void 自己検証_合成違反を検出する() {
        // Given: 今回の実欠陥と同型の合成違反（"The worker's check-in for \"{0}\" ..."）
        List<String> violations = findViolations("synthetic.key", "The worker's check-in for \"{0}\" was recorded.");
        assertThat(violations)
                .as("検出器が自分の偽陰性を晒さないための自己検証: 奇数個の ' を含むプレースホルダ値は検出されるはず")
                .isNotEmpty();

        // 偽陽性確認: 正しくエスケープ済み（''）や プレースホルダなしは違反にならない。
        assertThat(findViolations("synthetic.ok1", "The worker''s check-in for \"{0}\" was recorded."))
                .as("アポストロフィが偶数個（正しいエスケープ）なら違反ではない")
                .isEmpty();
        assertThat(findViolations("synthetic.ok2", "The worker's check-in was recorded."))
                .as("プレースホルダを含まない値は対象外")
                .isEmpty();
    }

    @Test
    @DisplayName("全 messages*.properties: プレースホルダを含む値に奇数個の ' が混在する行が無い")
    void 全ロケールファイル横断_違反なし() throws IOException {
        List<String> allViolations = new ArrayList<>();
        for (String resourceName : RESOURCE_NAMES) {
            Properties props = loadProperties(resourceName);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                List<String> violations = findViolations(key, value);
                for (String v : violations) {
                    allViolations.add(resourceName + ": " + v);
                }
            }
        }

        assertThat(allViolations)
                .as("MessageFormat エスケープ事故（{0}等のプレースホルダ + 奇数個の'）が無いこと。" +
                        "検出された場合はアポストロフィを '' に二重化して修正すること: %s", allViolations)
                .isEmpty();
    }

    /** 1件の (key, value) を検査し、違反があれば説明文のリストを返す（無ければ空）。 */
    private static List<String> findViolations(String key, String value) {
        if (value == null || !PLACEHOLDER_PATTERN.matcher(value).find()) {
            return List.of();
        }
        long apostropheCount = value.chars().filter(c -> c == '\'').count();
        if (apostropheCount % 2 != 0) {
            return List.of(key + "=" + value + " (apostropheCount=" + apostropheCount + ")");
        }
        return List.of();
    }

    private static Properties loadProperties(String resourceName) throws IOException {
        Properties props = new Properties();
        try (var input = MessagesPropertiesApostropheGuardTest.class
                .getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(input).as("クラスパス上に %s が存在すること", resourceName).isNotNull();
            // messages*.properties は UTF-8（I18nConfig#messageSource の defaultEncoding と揃える）。
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }
        return props;
    }
}
