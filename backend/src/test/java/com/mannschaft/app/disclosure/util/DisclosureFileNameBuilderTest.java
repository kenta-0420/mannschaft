package com.mannschaft.app.disclosure.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DisclosureFileNameBuilder")
class DisclosureFileNameBuilderTest {

    @Test
    @DisplayName("基本的なファイル名生成（pdf 拡張子）")
    void buildBasic() {
        String name = DisclosureFileNameBuilder.of("pdf")
                .date(LocalDate.of(2026, 5, 7))
                .propertyName("サンプルマンション")
                .unitNumber("301")
                .build();
        assertThat(name).isEqualTo("20260507_重要事項説明書(参考)_サンプルマンション_301.pdf");
    }

    @Test
    @DisplayName("xlsx 拡張子")
    void buildXlsx() {
        String name = DisclosureFileNameBuilder.of("xlsx")
                .date(LocalDate.of(2026, 5, 7))
                .propertyName("テスト")
                .unitNumber("101")
                .build();
        assertThat(name).endsWith(".xlsx");
    }

    @Test
    @DisplayName("propertyName/unitNumber 省略時")
    void buildWithoutOptional() {
        String name = DisclosureFileNameBuilder.of("pdf")
                .date(LocalDate.of(2026, 5, 7))
                .build();
        assertThat(name).isEqualTo("20260507_重要事項説明書(参考).pdf");
    }

    @Test
    @DisplayName("禁止文字は _ にサニタイズされる")
    void sanitizeForbiddenChars() {
        String name = DisclosureFileNameBuilder.of("pdf")
                .date(LocalDate.of(2026, 5, 7))
                .propertyName("A/B\\C:D*E?F\"G<H>I|J")
                .build();
        assertThat(name).contains("A_B_C_D_E_F_G_H_I_J");
        assertThat(name).doesNotContain("/", "\\", ":", "*", "?", "\"", "<", ">", "|");
    }

    @Test
    @DisplayName("date 未設定で build → IllegalArgumentException 系")
    void dateRequired() {
        assertThatThrownBy(() -> DisclosureFileNameBuilder.of("pdf").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("date");
    }

    @Test
    @DisplayName("buildEncoded は UTF-8 RFC 5987 エンコード")
    void buildEncoded() {
        String encoded = DisclosureFileNameBuilder.of("pdf")
                .date(LocalDate.of(2026, 5, 7))
                .propertyName("テスト")
                .unitNumber("301")
                .buildEncoded();
        assertThat(encoded).doesNotContain("テスト");
        assertThat(encoded).contains("%E3");
        assertThat(encoded).doesNotContain("+");
    }
}
