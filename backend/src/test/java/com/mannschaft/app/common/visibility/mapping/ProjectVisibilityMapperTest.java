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
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_AND_ABOVE（W5 内輪・応援者除外）")
    void members_only_maps_to_MEMBERS_AND_ABOVE() {
        // 判定根拠: ProjectVisibility.PUBLIC は「SUPPORTER も閲覧可」、設計書 F02.3 §権限表で
        // SUPPORTER は「公開プロジェクトの閲覧（TODO は対象外）」のみ。MEMBERS_ONLY は応援者を
        // 含まない内輪の意図が確定。W3 の SCOPE_AFFILIATED から MEMBERS_AND_ABOVE へ締め直し。
        // 挙動変更: 直接所属の SUPPORTER は MEMBERS_ONLY プロジェクトを閲覧できなくなる。
        assertThat(ProjectVisibilityMapper.toStandard(ProjectVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(ProjectVisibilityMapper.toStandard(ProjectVisibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }
}
