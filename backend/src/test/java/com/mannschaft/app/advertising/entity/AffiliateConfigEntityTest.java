package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.advertising.AdPlacement;
import com.mannschaft.app.advertising.AffiliateProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AffiliateConfigEntity#isPlaceholderTagId} 単体テスト（CMP-260918-0025）。
 *
 * <p>Flyway V149.20260710004057 が投入する未設定プレースホルダ tag_id
 * （{@code PLACEHOLDER_AMAZON_TAG} / {@code PLACEHOLDER_RAKUTEN_TAG}）を
 * 広告候補・管理画面の警告表示のいずれからも同じ基準で判定できることを保証する。</p>
 */
@DisplayName("AffiliateConfigEntity#isPlaceholderTagId 単体テスト")
class AffiliateConfigEntityTest {

    @Test
    @DisplayName("正常系: PLACEHOLDER_ 接頭辞の tag_id はプレースホルダと判定される")
    void プレースホルダ接頭辞はtrue() {
        assertThat(AffiliateConfigEntity.isPlaceholderTagId("PLACEHOLDER_AMAZON_TAG")).isTrue();
        assertThat(AffiliateConfigEntity.isPlaceholderTagId("PLACEHOLDER_RAKUTEN_TAG")).isTrue();
    }

    @Test
    @DisplayName("正常系: 本物の tag_id はプレースホルダと判定されない")
    void 本物のtagIdはfalse() {
        assertThat(AffiliateConfigEntity.isPlaceholderTagId("mannschaft-22")).isFalse();
    }

    @Test
    @DisplayName("正常系: null はプレースホルダと判定されない")
    void nullはfalse() {
        assertThat(AffiliateConfigEntity.isPlaceholderTagId(null)).isFalse();
    }

    @Test
    @DisplayName("正常系: エンティティのインスタンスメソッドも同じ結果を返す")
    void インスタンスメソッドも同じ結果() {
        AffiliateConfigEntity placeholder = AffiliateConfigEntity.builder()
                .provider(AffiliateProvider.AMAZON)
                .tagId("PLACEHOLDER_AMAZON_TAG")
                .placement(AdPlacement.DASHBOARD_TILE)
                .build();
        AffiliateConfigEntity real = AffiliateConfigEntity.builder()
                .provider(AffiliateProvider.AMAZON)
                .tagId("mannschaft-22")
                .placement(AdPlacement.DASHBOARD_TILE)
                .build();

        assertThat(placeholder.isPlaceholderTagId()).isTrue();
        assertThat(real.isPlaceholderTagId()).isFalse();
    }
}
