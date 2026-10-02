package com.mannschaft.app.survey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * 試練（#2617-3）— {@link ResultsVisibility} への {@code ALWAYS} 追加。
 *
 * <p>設計書 {@code docs/features/F05.4_survey_vote.md} §72/§107/§1000/§1049/§1619-1627/§1719 は
 * 「{@code PUBLISHED} になった時点から締切前も含め、配信対象スコープの会員全員が中間集計を
 * 閲覧できる」設定を定めている。既存の {@code AFTER_CLOSE}（全員向けだが締切後に限る）から
 * <b>時間制約を外したもの</b>にあたる。</p>
 *
 * <p>実装前は {@code ALWAYS} が存在しないため {@code valueOf} が
 * {@link IllegalArgumentException} を投げて red となる（コンパイル不能を避けるため
 * 定数を直接参照せず名前で引く）。</p>
 *
 * <p>担保する受け入れ条件: <b>AC-7</b>。</p>
 */
@DisplayName("ResultsVisibility — ALWAYS の追加（AC-7）")
class ResultsVisibilityAlwaysTest {

    @Test
    @DisplayName("AC-7: ALWAYS が enum 値として存在する")
    void ac7_alwaysExists() {
        assertThat(Arrays.stream(ResultsVisibility.values()).map(Enum::name))
                .as("AC-7: 設計書 F05.4 の「公開直後から会員全員が閲覧可」を表す値")
                .contains("ALWAYS");

        assertThat(ResultsVisibility.valueOf("ALWAYS")).isNotNull();
    }

    @Test
    @DisplayName("AC-7: 既存4値は削除・改名されない（回帰防止）")
    void ac7_existingValuesAreKept() {
        assertThat(Arrays.stream(ResultsVisibility.values()).map(Enum::name))
                .contains("AFTER_RESPONSE", "AFTER_CLOSE", "ADMINS_ONLY", "VIEWERS_ONLY");
    }
}
