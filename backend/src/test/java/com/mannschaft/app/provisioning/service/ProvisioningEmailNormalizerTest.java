package com.mannschaft.app.provisioning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 柱②-2 試練: 招待メールアドレス正規化の純ロジック UT（AC4 の前提となる正規化仕様）。
 *
 * <p>骨格は {@link UnsupportedOperationException} を投げるため、以下は red が正しい。
 * 実装は後続 PR（出陣）で行う。</p>
 */
class ProvisioningEmailNormalizerTest {

    private final ProvisioningEmailNormalizer normalizer = new ProvisioningEmailNormalizer();

    @Test
    @DisplayName("AC4前提: 大文字小文字違いは同一とみなす（lowercase化）")
    void normalizesCaseDifference() {
        assertThat(normalizer.normalize("User@Example.com")).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("AC4前提: NFD分解済み表現とNFC結合済み表現は同一とみなす（NFC正規化）")
    void normalizesUnicodeNormalizationForm() {
        // U+0065(e) + U+0301(結合アクセント) の NFD 表現と、U+00E9(単一コードポイント) の
        // NFC 表現は見た目は同じ文字だが、正規化前は別のバイト列であり単純 equals では不一致になる。
        String nfd = "caf" + "é" + "@example.com";
        String nfc = "caf" + "é" + "@example.com";
        assertThat(normalizer.normalize(nfd)).isEqualTo(normalizer.normalize(nfc));
    }
}
