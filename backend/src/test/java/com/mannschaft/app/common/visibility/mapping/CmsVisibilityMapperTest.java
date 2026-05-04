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
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_ONLY")
    void members_only_maps_to_MEMBERS_ONLY() {
        assertThat(CmsVisibilityMapper.toStandard(Visibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("SUPPORTERS_AND_ABOVE → StandardVisibility.SUPPORTERS_AND_ABOVE")
    void supporters_and_above_maps_to_SUPPORTERS_AND_ABOVE() {
        assertThat(CmsVisibilityMapper.toStandard(Visibility.SUPPORTERS_AND_ABOVE))
            .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
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
