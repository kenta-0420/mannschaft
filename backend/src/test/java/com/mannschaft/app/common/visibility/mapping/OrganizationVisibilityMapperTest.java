package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link OrganizationVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。
 */
@DisplayName("OrganizationVisibilityMapper")
class OrganizationVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(OrganizationEntity.Visibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(OrganizationEntity.Visibility v) {
        assertThat(OrganizationVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(OrganizationVisibilityMapper.toStandard(OrganizationEntity.Visibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("PRIVATE → StandardVisibility.SCOPE_AFFILIATED（外部非公開・メンバー閲覧可 / 挙動不変・名称正準化 W3）")
    void private_maps_to_MEMBERS_ONLY() {
        // 挙動不変: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
        assertThat(OrganizationVisibilityMapper.toStandard(OrganizationEntity.Visibility.PRIVATE))
            .isEqualTo(StandardVisibility.SCOPE_AFFILIATED);
    }
}
