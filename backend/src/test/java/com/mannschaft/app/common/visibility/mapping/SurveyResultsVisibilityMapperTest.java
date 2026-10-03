package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.survey.ResultsVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link SurveyResultsVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("SurveyResultsVisibilityMapper")
class SurveyResultsVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(ResultsVisibility.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(ResultsVisibility v) {
        assertThat(SurveyResultsVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("AFTER_RESPONSE -> CUSTOM (時間軸条件、Resolver 個別実装)")
    void mapsAfterResponse() {
        assertThat(SurveyResultsVisibilityMapper.toStandard(ResultsVisibility.AFTER_RESPONSE))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    @Test
    @DisplayName("AFTER_CLOSE -> CUSTOM (時間軸条件、Resolver 個別実装)")
    void mapsAfterClose() {
        assertThat(SurveyResultsVisibilityMapper.toStandard(ResultsVisibility.AFTER_CLOSE))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    @Test
    @DisplayName("ADMINS_ONLY -> ADMINS_AND_ABOVE（挙動不変・名称正準化 W4）")
    void mapsAdminsOnly() {
        // 挙動不変: ADMINS_AND_ABOVE = hasRoleOrAbove("ADMIN") = 旧 ADMINS_ONLY と同一判定。
        assertThat(SurveyResultsVisibilityMapper.toStandard(ResultsVisibility.ADMINS_ONLY))
            .isEqualTo(StandardVisibility.ADMINS_AND_ABOVE);
    }

    @Test
    @DisplayName("VIEWERS_ONLY -> CUSTOM (限定リスト、Resolver 個別実装)")
    void mapsViewersOnly() {
        assertThat(SurveyResultsVisibilityMapper.toStandard(ResultsVisibility.VIEWERS_ONLY))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    /**
     * AC-15 — 全値について写像が漏れなく返ること（新値追加時の取りこぼし検出）。
     *
     * <p>{@code switch} 式は網羅していなければコンパイルエラーになるが、
     * 「新値を追加したが写像を CUSTOM で握りつぶした」等の抜けは検出できないため、
     * 実際に全値を通して例外を投げないことを実行時に確認する。
     * {@code ALWAYS} 追加前は次の {@link #ac15_alwaysIsMapped()} が red となる。</p>
     */
    @ParameterizedTest
    @EnumSource(ResultsVisibility.class)
    @DisplayName("AC-15: 全値が例外なく写像される（新値追加時の取りこぼし検出）")
    void ac15_everyValueIsMappedWithoutThrowing(ResultsVisibility v) {
        assertThat(SurveyResultsVisibilityMapper.toStandard(v))
            .as("AC-15: %s の写像が未定義（新値の写像漏れ）", v)
            .isNotNull();
    }

    /**
     * AC-15 / AC-7 — {@code ALWAYS} も写像対象に含まれること。
     *
     * <p>実装前は {@code valueOf} が {@link IllegalArgumentException} を投げて red。</p>
     */
    @Test
    @DisplayName("AC-15: ALWAYS も写像される（未定義なら red）")
    void ac15_alwaysIsMapped() {
        assertThat(SurveyResultsVisibilityMapper.toStandard(ResultsVisibility.valueOf("ALWAYS")))
            .as("AC-15: ALWAYS の写像が未定義")
            .isNotNull();
    }
}
