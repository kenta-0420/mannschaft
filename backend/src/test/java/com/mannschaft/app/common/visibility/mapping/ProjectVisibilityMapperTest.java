package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.todo.ProjectVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link ProjectVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。
 */
@DisplayName("ProjectVisibilityMapper")
class ProjectVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(ProjectVisibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(ProjectVisibility v) {
        assertThat(ProjectVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PRIVATE → StandardVisibility.PRIVATE")
    void private_maps_to_PRIVATE() {
        assertThat(ProjectVisibilityMapper.toStandard(ProjectVisibility.PRIVATE))
            .isEqualTo(StandardVisibility.PRIVATE);
    }

    @Test
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_ONLY")
    void members_only_maps_to_MEMBERS_ONLY() {
        assertThat(ProjectVisibilityMapper.toStandard(ProjectVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(ProjectVisibilityMapper.toStandard(ProjectVisibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }
}
