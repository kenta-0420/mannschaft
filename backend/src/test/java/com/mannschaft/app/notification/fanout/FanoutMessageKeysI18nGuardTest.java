package com.mannschaft.app.notification.fanout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2871 fan-out 経路 i18n 化の番人（AC-2 / AC-7）。
 *
 * <p>fan-out の 4 経路（アンケート公開 / キープ変換 / シフト公開 / 村行事 4 種）が使う
 * メッセージキーが、<b>7 本すべての {@code messages*.properties}</b>（base ＋ 6 配信ロケール）に
 * 揃っていることを機械的に固定する。1 本でも欠けると当該ロケールの受信者だけが
 * {@code NoSuchMessageException} で配信落ちするが、その事故は本番の一部ロケールでしか
 * 顕在化しないため、テストで先に落とす。</p>
 *
 * <p><b>AC-7（en に日本語が混ざらない）</b>: 翻訳を怠って日本語をコピーしただけの
 * {@code messages_en.properties} を検出する。properties の値は<b>枠（アプリが書く文言）だけ</b>で
 * あり、利用者が書いた中身（アンケート名・行事名・予定名）は実行時に {@code {0}} へ差し込まれる。
 * よって値そのものへ日本語文字の正規表現をかけても、利用者コンテンツによる偽陽性は生じない
 * （＝「枠だけを見る」という AC-7 の要求をファイル構造そのものが満たしている）。</p>
 */
@DisplayName("fan-out i18n キー番人（Issue #2871・AC-2 / AC-7）")
class FanoutMessageKeysI18nGuardTest {

    /** 配信 bucket 6 種 ＋ base（フォールバック資源）。 */
    private static final List<String> RESOURCE_NAMES = List.of(
            "messages.properties",
            "messages_ja.properties",
            "messages_en.properties",
            "messages_zh.properties",
            "messages_ko.properties",
            "messages_es.properties",
            "messages_de.properties");

    /** fan-out 4 経路（村は 4 分岐）が使う全キー。{@code FanoutMessageKind} と 1:1 で対応させる。 */
    private static final List<String> REQUIRED_KEYS = List.of(
            "notification.fanout.survey.published.title",
            "notification.fanout.survey.published.body",
            "notification.fanout.scheduleKeep.converted.title",
            "notification.fanout.scheduleKeep.converted.body",
            "notification.fanout.shift.published.title",
            "notification.fanout.shift.published.body",
            "notification.fanout.village.eventAdded.title",
            "notification.fanout.village.eventAdded.body",
            "notification.fanout.village.eventTomorrow.title",
            "notification.fanout.village.eventTomorrow.body",
            "notification.fanout.village.meetingConfirmed.title",
            "notification.fanout.village.meetingConfirmed.body",
            "notification.fanout.village.festivalStarted.title",
            "notification.fanout.village.festivalStarted.body");

    /** 日本語（ひらがな・カタカナ・漢字）の検出。AC-7 の判定軸。 */
    private static final Pattern JAPANESE = Pattern.compile("[\\u3041-\\u3096\\u30A1-\\u30FA\\u4E00-\\u9FA5]");

    @Test
    @DisplayName("AC-2: fan-out の全キーが 7 本すべての messages*.properties に存在する")
    void 全ロケールに全キーが存在する() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String resourceName : RESOURCE_NAMES) {
            Properties props = loadProperties(resourceName);
            for (String key : REQUIRED_KEYS) {
                String value = props.getProperty(key);
                if (value == null || value.isBlank()) {
                    missing.add(resourceName + " → " + key);
                }
            }
        }
        assertThat(missing)
                .as("fan-out 経路の i18n キーが欠けているロケールがある（欠けたロケールの受信者だけが配信落ちする）: %s",
                        missing)
                .isEmpty();
    }

    @Test
    @DisplayName("AC-7: messages_en.properties の fan-out 枠に日本語文字が混ざらない（未翻訳コピーの検出）")
    void en_の枠に日本語が混ざらない() throws IOException {
        Properties en = loadProperties("messages_en.properties");
        List<String> japaneseFrames = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            String value = en.getProperty(key);
            if (value == null) {
                // AC-2 のテストが別途落ちる。ここでは翻訳品質のみを見る。
                continue;
            }
            Matcher m = JAPANESE.matcher(value);
            if (m.find()) {
                japaneseFrames.add(key + " = " + value + "（検出文字: " + m.group() + "）");
            }
        }
        assertThat(japaneseFrames)
                .as("英語ロケールの枠に日本語が残っている（日本語のコピーで済ませていないか）: %s", japaneseFrames)
                .isEmpty();
    }

    @Test
    @DisplayName("自己検証: 日本語検出器は合成の未翻訳文字列を実際に検出し、純英語は検出しない")
    void 自己検証_日本語検出器() {
        assertThat(JAPANESE.matcher("新しいアンケートが公開されました").find())
                .as("検出器が自分の偽陰性を晒さないための自己検証")
                .isTrue();
        assertThat(JAPANESE.matcher("A new survey has been published").find())
                .as("純英語を誤検出しないこと")
                .isFalse();
    }

    private static Properties loadProperties(String resourceName) throws IOException {
        Properties props = new Properties();
        try (InputStream in = FanoutMessageKeysI18nGuardTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertThat(in).as("リソースが見つからない: %s", resourceName).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }
}
