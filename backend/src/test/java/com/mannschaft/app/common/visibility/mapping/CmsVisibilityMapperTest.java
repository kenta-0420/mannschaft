package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.common.visibility.StandardVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link CmsVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表に従い、
 * 全 enum 値が non-null な StandardVisibility に写像されることを担保する。
 */
@DisplayName("CmsVisibilityMapper")
class CmsVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(Visibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(Visibility v) {
        assertThat(CmsVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(CmsVisibilityMapper.toStandard(Visibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_AND_ABOVE（W2: 内輪=応援者除外）")
    void members_only_maps_to_MEMBERS_AND_ABOVE() {
        // W2: 設計書 F06.1 §「ブログ/活動記録 一覧」で MEMBERS_ONLY="MEMBER 以上"、
        // SUPPORTERS_AND_ABOVE="SUPPORTER 以上" と別値で定義（docs/features/F06.1_cms_blog.md L799-801/L1667-1668）。
        // cms enum は SUPPORTERS_AND_ABOVE を別途持つため MEMBERS_ONLY は内輪(i)の意図が確定。
        // Mapper 出力先のみ正準ラダー MEMBERS_AND_ABOVE へ変更（機能 enum 名・DB 値は据え置き＝④A）。
        assertThat(CmsVisibilityMapper.toStandard(Visibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }

    @Test
    @DisplayName("SUPPORTERS_AND_ABOVE → StandardVisibility.SUPPORTERS_AND_ABOVE")
    void supporters_and_above_maps_to_SUPPORTERS_AND_ABOVE() {
        assertThat(CmsVisibilityMapper.toStandard(Visibility.SUPPORTERS_AND_ABOVE))
            .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
    }

    @Test
    @DisplayName("MEMBERS_AND_ABOVE → StandardVisibility.MEMBERS_AND_ABOVE（#1341 新ラダー値名・FE送信値）")
    void members_and_above_maps_to_MEMBERS_AND_ABOVE() {
        // 可視性ラダー統一(#1341)で FE が送る新ラダー値名。旧 MEMBERS_ONLY と同一の可視範囲へ写像。
        assertThat(CmsVisibilityMapper.toStandard(Visibility.MEMBERS_AND_ABOVE))
            .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }

    @Test
    @DisplayName("ADMINS_AND_ABOVE → StandardVisibility.ADMINS_AND_ABOVE（#1341 新ラダー値名）")
    void admins_and_above_maps_to_ADMINS_AND_ABOVE() {
        assertThat(CmsVisibilityMapper.toStandard(Visibility.ADMINS_AND_ABOVE))
            .isEqualTo(StandardVisibility.ADMINS_AND_ABOVE);
    }

    @Test
    @DisplayName("SCOPE_AFFILIATED → StandardVisibility.SCOPE_AFFILIATED（直接所属軸・旧MEMBERS_ONLY相当の正準値）")
    void scope_affiliated_maps_to_SCOPE_AFFILIATED() {
        assertThat(CmsVisibilityMapper.toStandard(Visibility.SCOPE_AFFILIATED))
            .isEqualTo(StandardVisibility.SCOPE_AFFILIATED);
    }

    @Test
    @DisplayName("FOLLOWERS_ONLY → StandardVisibility.FOLLOWERS_ONLY")
    void followers_only_maps_to_FOLLOWERS_ONLY() {
        assertThat(CmsVisibilityMapper.toStandard(Visibility.FOLLOWERS_ONLY))
            .isEqualTo(StandardVisibility.FOLLOWERS_ONLY);
    }

    @Test
    @DisplayName("PRIVATE → StandardVisibility.PRIVATE")
    void private_maps_to_PRIVATE() {
        assertThat(CmsVisibilityMapper.toStandard(Visibility.PRIVATE))
            .isEqualTo(StandardVisibility.PRIVATE);
    }

    @Test
    @DisplayName("CUSTOM_TEMPLATE → StandardVisibility.CUSTOM_TEMPLATE")
    void custom_template_maps_to_CUSTOM_TEMPLATE() {
        assertThat(CmsVisibilityMapper.toStandard(Visibility.CUSTOM_TEMPLATE))
            .isEqualTo(StandardVisibility.CUSTOM_TEMPLATE);
    }
}
