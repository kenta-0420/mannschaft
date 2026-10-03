package com.mannschaft.app.common.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配信ロケール正規化の番人（Issue #2871・AC-4 の「正規化は一箇所」の中身）。
 *
 * <p>受信者ソースが 4 実装あるなかで正規化がバラつくと、たとえば {@code zh-CN} の利用者だけが
 * 「受信者ソースでは zh に落ちるが enqueue では zh-CN のまま」となって文面を引けず配信落ちする。
 * ここでは正規化そのものの入出力を固定する。呼び出し経路が必ずここを通ることは
 * {@code FanoutRecipient} のコンパクトコンストラクタで構造的に保証している
 * （{@link #受信者レコードは必ず正規化を通る()}）。</p>
 */
@DisplayName("配信ロケール正規化番人（Issue #2871）")
class DeliveryLocalesTest {

    @Test
    @DisplayName("配信 bucket はちょうど 6 種（base properties は bucket ではない）")
    void 配信bucketは6種() {
        assertThat(DeliveryLocales.TAGS)
                .as("base（サフィックス無し）はフォールバック資源であって配信ロケールではない")
                .containsExactly("ja", "en", "zh", "ko", "es", "de");
    }

    @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
    @CsvSource(nullValues = "NULL", value = {
            // 素直な 6 種はそのまま
            "ja, ja",
            "en, en",
            "zh, zh",
            "ko, ko",
            "es, es",
            "de, de",
            // null・空・空白は既定へ
            "NULL, ja",
            "'', ja",
            "'   ', ja",
            // 地域・スクリプトサブタグは落として言語だけ見る
            "zh-CN, zh",
            "zh-TW, zh",
            "zh_Hans, zh",
            "en-US, en",
            "es_MX, es",
            "de-AT, de",
            "pt-BR, ja",
            // 大文字小文字の揺れ
            "JA, ja",
            "En, en",
            "ZH-cn, zh",
            // 未対応言語は既定へ
            "fr, ja",
            "pt, ja",
            "xx-YY, ja",
    })
    @DisplayName("null・空・地域タグ付き・大文字・未対応言語をすべて 6 種のいずれかへ落とす")
    void 正規化(String raw, String expected) {
        assertThat(DeliveryLocales.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("正規化結果は必ず Locale へ解決でき、未正規化の値を渡しても既定へ落ちる（落とさない）")
    void ロケール解決() {
        for (String tag : DeliveryLocales.TAGS) {
            assertThat(DeliveryLocales.toLocale(tag))
                    .as("%s は Locale へ解決できる", tag)
                    .isNotNull();
        }
        assertThat(DeliveryLocales.toLocale("zh-CN"))
                .as("未正規化の値を渡されても例外にせず既定へ寄せる（配信を止めない）")
                .isEqualTo(DeliveryLocales.toLocale(DeliveryLocales.DEFAULT_TAG));
    }

    @Test
    @DisplayName("AC-4: FanoutRecipient は生の DB 値を渡されても必ず正規化を通す（呼び忘れが作れない）")
    void 受信者レコードは必ず正規化を通る() {
        assertThat(new com.mannschaft.app.notification.fanout.FanoutRecipient(1L, "zh-CN").locale())
                .as("受信者ソースが生の users.locale を渡してもレコード側で正規化される")
                .isEqualTo("zh");
        assertThat(new com.mannschaft.app.notification.fanout.FanoutRecipient(2L, null).locale())
                .as("users 行が無い（LEFT JOIN で NULL）場合も既定へ落ちる")
                .isEqualTo("ja");
    }
}
