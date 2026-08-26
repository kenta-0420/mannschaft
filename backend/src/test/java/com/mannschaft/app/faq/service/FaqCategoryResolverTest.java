package com.mannschaft.app.faq.service;

import com.mannschaft.app.faq.FaqCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FaqCategoryResolver} の単体テスト（F21.1 §5.5）。
 *
 * <p>チームの {@code template}（フロント大文字値 + F01.3 小文字スラッグ）と
 * 組織の {@code orgType}（enum 名）の双系統を {@link FaqCategory} へ写像する純粋関数を検証する。
 * 主要マッピング・大小文字無視・未知/null/空文字の GENERAL フォールバックを網羅する。</p>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5</p>
 */
@DisplayName("FaqCategoryResolver 単体テスト")
class FaqCategoryResolverTest {

    private final FaqCategoryResolver resolver = new FaqCategoryResolver();

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
            // --- SPORTS ---
            "CLUB,        SPORTS",
            "sports,      SPORTS",
            "gym,         SPORTS",
            // --- HEALTH ---
            "CLINIC,      HEALTH",
            "HOSPITAL,    HEALTH",
            "clinic,      HEALTH",
            // --- EDUCATION ---
            "CLASS,       EDUCATION",
            "SCHOOL,      EDUCATION",
            "school,      EDUCATION",
            // --- BUSINESS ---
            "COMPANY,     BUSINESS",
            "RESTAURANT,  BUSINESS",
            "STORE,       BUSINESS",
            "BEAUTY,      BUSINESS",
            "salon,       BUSINESS",
            // --- COMMUNITY ---
            "COMMUNITY,    COMMUNITY",
            "NEIGHBORHOOD, COMMUNITY",
            "VOLUNTEER,    COMMUNITY",
            "NPO,          COMMUNITY",
            "ASSOCIATION,  COMMUNITY",
            "GOVERNMENT,   COMMUNITY",
            "MUNICIPALITY, COMMUNITY",
            // --- RESIDENCE ---
            "CONDO,       RESIDENCE",
            "apartment,   RESIDENCE",
            "FAMILY,      RESIDENCE",
            // --- GENERAL（明示）---
            "OTHER,       GENERAL",
            "custom,      GENERAL"
    })
    @DisplayName("主要マッピング: template / orgType -> FaqCategory")
    void resolve_mapsKnownKeys(String raw, FaqCategory expected) {
        assertThat(resolver.resolve(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> GENERAL（未知）")
    @ValueSource(strings = {"UNKNOWN", "xyz", "team", "123"})
    @DisplayName("未知の値は GENERAL にフォールバックする")
    void resolve_unknownFallsBackToGeneral(String raw) {
        assertThat(resolver.resolve(raw)).isEqualTo(FaqCategory.GENERAL);
    }

    @ParameterizedTest(name = "[{index}] null / 空文字 / 空白 -> GENERAL")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("null / 空文字 / 空白のみは GENERAL にフォールバックする")
    void resolve_nullOrBlankFallsBackToGeneral(String raw) {
        assertThat(resolver.resolve(raw)).isEqualTo(FaqCategory.GENERAL);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" は大小文字無視で SPORTS")
    @ValueSource(strings = {"club", "Club", "CLUB", "cLuB", "  club  "})
    @DisplayName("大文字・小文字を無視し前後空白をトリムして解決する")
    void resolve_isCaseInsensitiveAndTrimmed(String raw) {
        assertThat(resolver.resolve(raw)).isEqualTo(FaqCategory.SPORTS);
    }
}
