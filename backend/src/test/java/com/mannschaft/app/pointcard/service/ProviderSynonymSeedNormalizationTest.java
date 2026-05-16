package com.mannschaft.app.pointcard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9.157 で投入するシノニム Seed の {@code synonym_normalized} 列が
 * {@link ProviderMatchService#normalize(String)} の出力と完全一致するかを保証する単体テスト。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §7.6
 *
 * <p>このテストが落ちた場合、Seed の normalized 列に誤りがあり、UNIQUE 制約衝突や
 * fuzzy match のすり抜けを引き起こす可能性がある。Seed と本テストは必ず同時更新すること。
 *
 * <p>テストデータは V9.157__seed_point_card_provider_synonyms.sql の
 * {@code synonym_display, synonym_normalized} ペアと完全一致させている。
 */
@DisplayName("V9.157 シノニム Seed の normalized 整合性テスト")
class ProviderSynonymSeedNormalizationTest {

    @ParameterizedTest(name = "[{index}] normalize(\"{0}\") == \"{1}\"")
    @CsvSource({
        // synonym_display, expected synonym_normalized
        "ドコモポイント,        どこもぽいんと",
        "Dポイ,                  dぽい",
        "Tポイント,              tぽいんと",
        "ティーポイント,         てぃーぽいんと",
        "楽天スーパーポイント,    楽天すーぱーぽいんと",
        "楽ポ,                   楽ぽ",
        "ペイペイ,               ぺいぺい",
        "PayPayポイント,         paypayぽいんと",
        "ロッピー,               ろっぴー",
        "Pontaカード,            pontaかーど",
        "東急ポイ,               東急ぽい",
        "ヨドバシポイント,       よどばしぽいんと",
        "ビックポイント,         びっくぽいんと",
        "マツキヨ,               まつきよ",
        "マツキヨポイント,       まつきよぽいんと",
        "ツタヤ,                 つたや",
        "ナナコ,                 ななこ",
        "セブンポイント,         せぶんぽいんと",
        "ワオン,                 わおん",
        "イオンポイント,         いおんぽいんと",
        "マック,                 まっく",
        "マクド,                 まくど",
        "スタバ,                 すたば",
        "ユニクロアプリ,         ゆにくろあぷり",
        "ファミマ,               ふぁみま",
        "ファミペイ,             ふぁみぺい"
    })
    void seed正規化値がProviderMatchServiceの正規化結果と一致する(String display, String expectedNormalized) {
        // synonym_display 入力 → ProviderMatchService.normalize で V9.157 Seed と同じ normalized が得られる
        String actual = ProviderMatchService.normalize(display.trim());
        assertThat(actual).isEqualTo(expectedNormalized.trim());
    }
}
